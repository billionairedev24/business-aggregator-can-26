package ca.northline.catalogue.adapters.commerce;

import static ca.northline.catalogue.adapters.commerce.CommerceHttp.JSON;
import static ca.northline.catalogue.adapters.commerce.CommerceHttp.text;

import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.integration.Backoff;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Square Catalog + Inventory API (S-35), written from developer.squareup.com; never run against Square (no developer
 * account exists).
 *
 * <ul>
 *   <li><b>OAuth:</b> {@code /oauth2/authorize} (scopes {@code ITEMS_READ INVENTORY_READ MERCHANT_PROFILE_READ},
 *       {@code session=false}) → {@code POST /oauth2/token}. Access tokens last 30 days: refreshed once less than 7
 *       days remain (Square's advice); the code-flow refresh token doesn't change. Disconnect calls
 *       {@code /oauth2/revoke}.
 *   <li><b>Reads:</b> {@code POST /v2/catalog/search} (ITEM, with related IMAGE objects; archived items skipped), then
 *       {@code POST /v2/inventory/counts/batch-retrieve} for the variations (IN_STOCK, summed over locations).
 *   <li><b>Webhooks:</b> app-level subscription in the Developer Console ({@code catalog.version.updated},
 *       {@code inventory.count.updated}, {@code oauth.authorization.revoked}); {@code x-square-hmacsha256-signature} =
 *       base64 HMAC-SHA256 of notification URL + raw body with the subscription's signature key; deduplicated on
 *       {@code event_id}.
 *   <li>429s are retried with back-off ({@link Backoff}).
 * </ul>
 */
class SquareCatalogSource implements CommerceCatalogSource {

    static final Duration REFRESH_BEFORE = Duration.ofDays(7);

    interface Api {
        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode token(URI url, @RequestHeader("Square-Version") String version, @RequestBody Map<String, Object> body);

        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode post(
                URI url,
                @RequestHeader("Square-Version") String version,
                @RequestHeader("Authorization") String authorization,
                @RequestBody Map<String, Object> body);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode get(
                URI url,
                @RequestHeader("Square-Version") String version,
                @RequestHeader("Authorization") String authorization);
    }

    private final CommerceProperties.Square config;
    private final Api api;
    private final Backoff backoff;
    private final Clock clock;

    SquareCatalogSource(CommerceProperties.Square config, Api api, Backoff backoff, Clock clock) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public CommerceProvider provider() {
        return CommerceProvider.SQUARE;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    private URI url(String path) {
        return CommerceHttp.uri(config.baseUrl(), path, Map.of());
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri, @Nullable String shop) {
        var p = CommerceHttp.params();
        p.put("client_id", config.id());
        p.put("scope", config.scopes());
        p.put("session", "false");
        p.put("state", state);
        p.put("redirect_uri", redirectUri.toString());
        return CommerceHttp.uri(config.baseUrl(), "/oauth2/authorize", p);
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri, @Nullable String shop) {
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("client_secret", config.secret());
        body.put("code", params.getOrDefault("code", ""));
        body.put("grant_type", "authorization_code");
        body.put("redirect_uri", redirectUri.toString());
        var token = CommerceHttp.tokenCall(
                () -> backoff.call("Square token", () -> api.token(url("/oauth2/token"), config.apiVersion(), body)));
        var credentials = credentials(token, null);
        var merchant = credentials.account();
        var label = Optional.ofNullable(text(
                        call(
                                        credentials,
                                        () -> api.get(
                                                url("/v2/merchants/" + CommerceHttp.encode(merchant)),
                                                config.apiVersion(),
                                                "Bearer " + credentials.accessToken()))
                                .path("merchant"),
                        "business_name"))
                .orElse("Square · " + merchant);
        var scopes = Arrays.stream(config.scopes().split("\\s+")).collect(Collectors.toSet());
        return new Grant(credentials, merchant, label, scopes);
    }

    private Credentials credentials(JsonNode token, @Nullable Credentials previous) {
        var access = text(token, "access_token");
        var merchant =
                Optional.ofNullable(text(token, "merchant_id")).orElse(previous == null ? null : previous.account());
        if (access == null || merchant == null) {
            throw new IllegalStateException("Square token response incomplete");
        }
        var expires = text(token, "expires_at");
        var refresh = Optional.ofNullable(text(token, "refresh_token"))
                .orElse(previous == null ? null : previous.refreshToken());
        return new Credentials(access, refresh, expires == null ? null : Instant.parse(expires), merchant);
    }

    @Override
    public Credentials refresh(Credentials c) {
        var expires = c.expiresAt();
        if (expires == null || expires.isAfter(clock.instant().plus(REFRESH_BEFORE)) || c.refreshToken() == null) {
            return c;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("client_secret", config.secret());
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", c.refreshToken());
        var token = CommerceHttp.tokenCall(
                () -> backoff.call("Square refresh", () -> api.token(url("/oauth2/token"), config.apiVersion(), body)));
        return credentials(token, c);
    }

    @Override
    public Page products(Credentials c, @Nullable String cursor) {
        var body = new LinkedHashMap<String, Object>();
        body.put("object_types", List.of("ITEM"));
        body.put("include_related_objects", true);
        body.put("limit", 100);
        if (cursor != null) {
            body.put("cursor", cursor);
        }
        var page = call(c, () -> api.post(url("/v2/catalog/search"), config.apiVersion(), bearer(c), body));
        var images = images(page.path("related_objects"));
        var items = new ArrayList<JsonNode>();
        for (var o : page.path("objects")) {
            if (active(o)) {
                items.add(o);
            }
        }
        return new Page(products(c, items, images), text(page, "cursor"));
    }

    @Override
    public Optional<ExternalProduct> product(Credentials c, String externalId) {
        JsonNode found;
        try {
            found = call(
                    c,
                    () -> api.get(
                            CommerceHttp.uri(
                                    config.baseUrl(),
                                    "/v2/catalog/object/" + CommerceHttp.encode(externalId),
                                    Map.of("include_related_objects", "true")),
                            config.apiVersion(),
                            bearer(c)));
        } catch (HttpClientErrorException.NotFound _) {
            return Optional.empty();
        }
        var object = found.path("object");
        if (!"ITEM".equals(text(object, "type")) || !active(object)) {
            return Optional.empty();
        }
        return Optional.of(products(c, List.of(object), images(found.path("related_objects")))
                .getFirst());
    }

    private static boolean active(JsonNode item) {
        return !item.path("is_deleted").asBoolean(false)
                && !item.path("item_data").path("is_archived").asBoolean(false);
    }

    private static Map<String, URI> images(JsonNode related) {
        var out = new HashMap<String, URI>();
        for (var o : related) {
            var url = text(o.path("image_data"), "url");
            var id = text(o, "id");
            if ("IMAGE".equals(text(o, "type")) && url != null && id != null) {
                out.put(id, URI.create(url));
            }
        }
        return out;
    }

    /** Maps items and reads their variations' IN_STOCK counts (summed over locations) in batches of 100. */
    private List<ExternalProduct> products(Credentials c, List<JsonNode> items, Map<String, URI> images) {
        var variationIds = new ArrayList<String>();
        for (var item : items) {
            for (var v : item.path("item_data").path("variations")) {
                var id = text(v, "id");
                if (id != null) {
                    variationIds.add(id);
                }
            }
        }
        var stock = new HashMap<String, Integer>();
        for (int i = 0; i < variationIds.size(); i += 100) {
            var batch = variationIds.subList(i, Math.min(variationIds.size(), i + 100));
            String cursor = null;
            do {
                var body = new LinkedHashMap<String, Object>();
                body.put("catalog_object_ids", batch);
                body.put("states", List.of("IN_STOCK"));
                if (cursor != null) {
                    body.put("cursor", cursor);
                }
                var counts = call(
                        c,
                        () -> api.post(
                                url("/v2/inventory/counts/batch-retrieve"), config.apiVersion(), bearer(c), body));
                for (var count : counts.path("counts")) {
                    var id = text(count, "catalog_object_id");
                    if (id != null) {
                        var quantity = new java.math.BigDecimal(String.valueOf(text(count, "quantity")))
                                .setScale(0, java.math.RoundingMode.DOWN)
                                .intValue();
                        stock.merge(id, quantity, Integer::sum);
                    }
                }
                cursor = text(counts, "cursor");
            } while (cursor != null);
        }
        var out = new ArrayList<ExternalProduct>();
        for (var item : items) {
            var data = item.path("item_data");
            var pictures = new ArrayList<URI>();
            for (var id : data.path("image_ids")) {
                var url = images.get(id.asString());
                if (url != null) {
                    pictures.add(url);
                }
            }
            var variants = new ArrayList<ExternalVariant>();
            for (var v : data.path("variations")) {
                var vd = v.path("item_variation_data");
                var id = String.valueOf(text(v, "id"));
                variants.add(new ExternalVariant(
                        id,
                        text(vd, "sku"),
                        text(vd, "upc"),
                        Optional.ofNullable(text(vd, "name")).orElse(""),
                        Map.of(),
                        vd.path("price_money").path("amount").asLong(0),
                        stock.getOrDefault(id, 0),
                        id));
            }
            if (variants.isEmpty()) {
                continue;
            }
            var updated = text(item, "updated_at");
            out.add(new ExternalProduct(
                    String.valueOf(text(item, "id")),
                    Optional.ofNullable(text(data, "name")).orElse(""),
                    Optional.ofNullable(text(data, "description_html")).orElse(text(data, "description")),
                    null,
                    pictures,
                    variants,
                    updated == null ? null : Instant.parse(updated)));
        }
        return out;
    }

    /** The subscription is app-level (Developer Console): active when its signature key is configured. */
    @Override
    public boolean subscribe(Credentials credentials, URI callbackUrl) {
        return !config.signatureKey().isEmpty();
    }

    @Override
    public void revoke(Credentials c) {
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("access_token", c.accessToken());
        backoff.call(
                "Square revoke",
                () -> api.post(url("/oauth2/revoke"), config.apiVersion(), "Client " + config.secret(), body));
    }

    @Override
    public Delivery verify(WebhookRequest request) {
        var key = config.signatureKey();
        if (key.isEmpty()) {
            throw new Unverified("no signature key configured");
        }
        var signed =
                (request.url() + new String(request.body(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        var expected = Base64.getEncoder().encodeToString(CommerceHttp.hmacSha256(key, signed));
        if (!CommerceHttp.same(request.header("x-square-hmacsha256-signature"), expected)) {
            throw new Unverified("signature");
        }
        var body = JSON.readTree(request.body());
        var merchant = text(body, "merchant_id");
        var eventId = text(body, "event_id");
        var type = String.valueOf(text(body, "type"));
        if (merchant == null || eventId == null) {
            throw new Unverified("payload");
        }
        var changes = new ArrayList<Change>();
        switch (type) {
            case "catalog.version.updated" -> changes.add(new Change.CatalogChanged());
            case "inventory.count.updated" -> {
                for (var count : body.path("data").path("object").path("inventory_counts")) {
                    var id = text(count, "catalog_object_id");
                    if (id != null) {
                        changes.add(new Change.StockChanged(id));
                    }
                }
            }
            case "oauth.authorization.revoked" -> changes.add(new Change.Uninstalled());
            default -> {}
        }
        return new Delivery(merchant, eventId, changes);
    }

    private static String bearer(Credentials c) {
        return "Bearer " + c.accessToken();
    }

    private JsonNode call(Credentials c, java.util.function.Supplier<JsonNode> call) {
        return backoff.call("Square " + c.account(), () -> CommerceHttp.authorized(call));
    }
}
