package ca.northline.catalogue.adapters.commerce;

import static ca.northline.catalogue.adapters.commerce.CommerceHttp.JSON;
import static ca.northline.catalogue.adapters.commerce.CommerceHttp.text;

import ca.northline.catalogue.adapters.commerce.CommerceHttp.Backoff;
import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.domain.CommerceProvider;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Lightspeed Retail (X-Series) API (S-35), written from x-series-api.lightspeedhq.com; never run against a store (no
 * developer account exists; the docs site was also unreachable from the build environment, so field names follow the
 * published 2.0 reference as last known — see docs/runbooks/commerce-sync.md).
 *
 * <ul>
 *   <li><b>OAuth:</b> {@code https://secure.retail.lightspeed.app/connect?response_type=code…}; the callback adds
 *       {@code domain_prefix} (checked: letters, digits, dashes — it becomes a host name), then
 *       {@code POST https://{domain_prefix}.retail.lightspeed.app/api/1.0/token}. Access tokens are short-lived and
 *       refresh tokens rotate: both are re-sealed on refresh. No revocation endpoint exists; disconnect destroys the
 *       tokens and the runbook tells the merchant where to remove the add-on.
 *   <li><b>Reads:</b> {@code GET /api/2.0/products?after=<version>} until an empty page, grouped into families by
 *       {@code variant_parent_id}; {@code GET /api/2.0/inventory} summed over outlets. Inactive and deleted products
 *       are skipped. 429s wait for {@code Retry-After}.
 *   <li><b>Webhooks:</b> {@code POST /api/webhooks} ({@code product.update}, {@code inventory.update}); deliveries are
 *       form posts ({@code payload}, {@code type}, {@code domain_prefix}) signed in {@code X-Signature:
 *       signature=…, algorithm=HMAC-SHA256} over the raw body with the app's client secret; deduplicated on a hash of
 *       the body (X-Series sends no delivery id).
 * </ul>
 */
@Slf4j
class LightspeedCatalogSource implements CommerceCatalogSource {

    static final Pattern DOMAIN_PREFIX = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);
    static final List<String> WEBHOOKS = List.of("product.update", "inventory.update");

    interface Api {
        @PostExchange(
                contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode token(URI url, @RequestBody MultiValueMap<String, String> form);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode get(URI url, @RequestHeader("Authorization") String authorization);

        @PostExchange(
                contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode webhook(
                URI url,
                @RequestHeader("Authorization") String authorization,
                @RequestBody MultiValueMap<String, String> form);
    }

    private final CommerceProperties.Lightspeed config;
    private final Api api;
    private final Backoff backoff;
    private final Clock clock;

    LightspeedCatalogSource(CommerceProperties.Lightspeed config, Api api, Backoff backoff, Clock clock) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public CommerceProvider provider() {
        return CommerceProvider.LIGHTSPEED;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    private String base(String domainPrefix) {
        if (!DOMAIN_PREFIX.matcher(domainPrefix).matches()) {
            throw new Unverified("domain_prefix");
        }
        return config.apiUrl().replace("{domain_prefix}", domainPrefix);
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri, @Nullable String shop) {
        var p = CommerceHttp.params();
        p.put("response_type", "code");
        p.put("client_id", config.id());
        p.put("redirect_uri", redirectUri.toString());
        p.put("state", state);
        if (!config.scopes().isBlank()) {
            p.put("scope", config.scopes());
        }
        return URI.create(config.authUrl() + CommerceHttp.query(p));
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri, @Nullable String shop) {
        var prefix = params.getOrDefault("domain_prefix", "");
        var token = CommerceHttp.tokenCall(() -> backoff.call(
                "Lightspeed token",
                () -> api.token(
                        CommerceHttp.uri(base(prefix), "/api/1.0/token", Map.of()),
                        CommerceHttp.form(
                                "code", params.getOrDefault("code", ""),
                                "client_id", config.id(),
                                "client_secret", config.secret(),
                                "grant_type", "authorization_code",
                                "redirect_uri", redirectUri.toString()))));
        var credentials = credentials(token, prefix, null);
        return new Grant(
                credentials,
                prefix,
                prefix + ".retail.lightspeed.app",
                config.scopes().isBlank() ? Set.of() : Set.of(config.scopes().split("\\s+")));
    }

    private Credentials credentials(JsonNode token, String prefix, @Nullable String previousRefresh) {
        var access = text(token, "access_token");
        if (access == null) {
            throw new IllegalStateException("Lightspeed returned no access token");
        }
        var expires = token.path("expires").asLong(0);
        var at = expires > 0
                ? Instant.ofEpochSecond(expires)
                : clock.instant().plusSeconds(token.path("expires_in").asLong(3600));
        var refresh = Optional.ofNullable(text(token, "refresh_token")).orElse(previousRefresh);
        return new Credentials(access, refresh, at, prefix);
    }

    @Override
    public Credentials refresh(Credentials c) {
        var expires = c.expiresAt();
        var refresh = c.refreshToken();
        if (expires == null
                || refresh == null
                || expires.isAfter(clock.instant().plus(REFRESH_BEFORE))) {
            return c;
        }
        var token = CommerceHttp.tokenCall(() -> backoff.call(
                "Lightspeed refresh",
                () -> api.token(
                        CommerceHttp.uri(base(c.account()), "/api/1.0/token", Map.of()),
                        CommerceHttp.form(
                                "refresh_token",
                                refresh,
                                "client_id",
                                config.id(),
                                "client_secret",
                                config.secret(),
                                "grant_type",
                                "refresh_token"))));
        return credentials(token, c.account(), refresh);
    }

    /** The whole catalogue in one page: a family's variants can sit on different API pages, so they are grouped here. */
    @Override
    public Page products(Credentials c, @Nullable String cursor) {
        var rows = new ArrayList<JsonNode>();
        long after = 0;
        while (true) {
            var page = get(c, "/api/2.0/products", Map.of("after", Long.toString(after), "page_size", "200"));
            var data = page.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            data.forEach(rows::add);
            var max = page.path("version").path("max").asLong(0);
            if (max <= after) {
                break;
            }
            after = max;
        }
        return new Page(families(rows, inventory(c)), null);
    }

    @Override
    public Optional<ExternalProduct> product(Credentials c, String externalId) {
        JsonNode row;
        try {
            row = get(c, "/api/2.0/products/" + CommerceHttp.encode(externalId), Map.of())
                    .path("data");
        } catch (HttpClientErrorException.NotFound _) {
            return Optional.empty();
        }
        if (row.isMissingNode() || !active(row)) {
            return Optional.empty();
        }
        if (row.path("has_variants").asBoolean(false) || text(row, "variant_parent_id") != null) {
            // a family: read everything and pick it (the 2.0 API has no "variants of" call)
            var family = Optional.ofNullable(text(row, "variant_parent_id")).orElse(externalId);
            return products(c, null).items().stream()
                    .filter(p -> p.id().equals(family))
                    .findFirst();
        }
        var levels = new HashMap<String, Integer>();
        for (var level : get(c, "/api/2.0/products/" + CommerceHttp.encode(externalId) + "/inventory", Map.of())
                .path("data")) {
            levels.merge(externalId, level.path("inventory_level").asInt(0), Integer::sum);
        }
        return families(List.of(row), levels).stream().findFirst();
    }

    private Map<String, Integer> inventory(Credentials c) {
        var levels = new HashMap<String, Integer>();
        long after = 0;
        while (true) {
            var page = get(c, "/api/2.0/inventory", Map.of("after", Long.toString(after), "page_size", "500"));
            var data = page.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            for (var level : data) {
                var id = text(level, "product_id");
                if (id != null) {
                    levels.merge(id, level.path("inventory_level").asInt(0), Integer::sum);
                }
            }
            var max = page.path("version").path("max").asLong(0);
            if (max <= after) {
                break;
            }
            after = max;
        }
        return levels;
    }

    private static boolean active(JsonNode row) {
        return row.path("is_active").asBoolean(true)
                && (row.path("deleted_at").isMissingNode()
                        || row.path("deleted_at").isNull());
    }

    /** Families by {@code variant_parent_id}: the parent row carries name, description and images. */
    static List<ExternalProduct> families(List<JsonNode> rows, Map<String, Integer> levels) {
        var byFamily = new LinkedHashMap<String, List<JsonNode>>();
        var parents = new HashMap<String, JsonNode>();
        for (var row : rows) {
            if (!active(row)) {
                continue;
            }
            var id = String.valueOf(text(row, "id"));
            var parent = text(row, "variant_parent_id");
            if (parent == null) {
                parents.put(id, row);
                if (!row.path("has_variants").asBoolean(false)) {
                    byFamily.computeIfAbsent(id, _ -> new ArrayList<>()).add(row);
                } else {
                    byFamily.computeIfAbsent(id, _ -> new ArrayList<>());
                }
            } else {
                byFamily.computeIfAbsent(parent, _ -> new ArrayList<>()).add(row);
            }
        }
        var out = new ArrayList<ExternalProduct>();
        byFamily.forEach((family, members) -> {
            var head = parents.get(family);
            if (members.isEmpty() || head == null) {
                return; // a parent without active variants, or variants of an inactive parent
            }
            var variants = new ArrayList<ExternalVariant>();
            for (var m : members) {
                var id = String.valueOf(text(m, "id"));
                var options = new LinkedHashMap<String, String>();
                for (var o : m.path("variant_options")) {
                    var name = text(o, "name");
                    var value = text(o, "value");
                    if (name != null && value != null) {
                        options.put(name, value);
                    }
                }
                variants.add(new ExternalVariant(
                        id,
                        text(m, "sku"),
                        barcode(m),
                        Optional.ofNullable(text(m, "variant_name")).orElse(String.valueOf(text(m, "name"))),
                        options,
                        CommerceHttp.cents(m.path("price_excluding_tax")),
                        levels.getOrDefault(id, 0),
                        id));
            }
            var images = new ArrayList<URI>();
            for (var image : head.path("images")) {
                var url = Optional.ofNullable(text(image.path("sizes"), "original"))
                        .orElse(text(image, "url"));
                if (url != null) {
                    images.add(URI.create(url));
                }
            }
            if (images.isEmpty()) {
                var url = text(head, "image_url");
                if (url != null && !url.contains("no-image")) {
                    images.add(URI.create(url));
                }
            }
            var updated = text(head, "updated_at");
            out.add(new ExternalProduct(
                    family,
                    String.valueOf(text(head, "name")),
                    text(head, "description"),
                    text(head.path("brand"), "name"),
                    images,
                    variants,
                    updated == null ? null : parseInstant(updated)));
        });
        return out;
    }

    private static @Nullable Instant parseInstant(String s) {
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (java.time.format.DateTimeParseException _) {
            return null;
        }
    }

    private static @Nullable String barcode(JsonNode row) {
        for (var code : row.path("product_codes")) {
            var type = text(code, "type");
            if (type != null
                    && Set.of("UPC", "EAN", "ISBN", "GTIN").contains(type.toUpperCase(java.util.Locale.ROOT))) {
                return text(code, "code");
            }
        }
        return null;
    }

    @Override
    public boolean subscribe(Credentials c, URI callbackUrl) {
        var ok = true;
        for (var type : WEBHOOKS) {
            var data = JSON.createObjectNode()
                    .put("url", callbackUrl.toString())
                    .put("active", true)
                    .put("type", type);
            try {
                backoff.call(
                        "Lightspeed webhook",
                        () -> CommerceHttp.authorized(() -> api.webhook(
                                CommerceHttp.uri(base(c.account()), "/api/webhooks", Map.of()),
                                "Bearer " + c.accessToken(),
                                CommerceHttp.form("data", data.toString()))));
            } catch (HttpClientErrorException e) {
                log.warn("Lightspeed webhook {} for {} refused: {}", type, c.account(), e.getStatusCode());
                ok = false;
            }
        }
        return ok;
    }

    /** X-Series has no endpoint to revoke a token: it is destroyed, and the merchant removes the add-on in the store. */
    @Override
    public void revoke(Credentials credentials) {}

    @Override
    public Delivery verify(WebhookRequest request) {
        if (!config.configured()) {
            throw new Unverified("not configured");
        }
        var presented = signature(request.header("x-signature"));
        var mac = CommerceHttp.hmacSha256(config.secret(), request.body());
        if (!CommerceHttp.same(presented, HexFormat.of().formatHex(mac))
                && !CommerceHttp.same(presented, Base64.getEncoder().encodeToString(mac))) {
            throw new Unverified("signature");
        }
        var form = parseForm(new String(request.body(), StandardCharsets.UTF_8));
        var prefix = form.get("domain_prefix");
        var type = form.getOrDefault("type", "");
        var payload = JSON.readTree(form.getOrDefault("payload", "{}"));
        if (prefix == null) {
            throw new Unverified("domain_prefix");
        }
        var changes = new ArrayList<Change>();
        switch (type) {
            case "product.update" -> {
                var id = text(payload, "id");
                var parent = text(payload, "variant_parent_id");
                if (id != null) {
                    var deleted = !payload.path("deleted_at").isMissingNode()
                            && !payload.path("deleted_at").isNull();
                    changes.add(
                            deleted && parent == null
                                    ? new Change.ProductRemoved(id)
                                    : new Change.ProductChanged(parent == null ? id : parent));
                }
            }
            case "inventory.update" -> {
                var id = Optional.ofNullable(text(payload, "product_id")).orElse(text(payload.path("product"), "id"));
                if (id != null) {
                    changes.add(new Change.StockChanged(id));
                }
            }
            default -> {}
        }
        return new Delivery(prefix, sha256(request.body()), changes);
    }

    /** {@code signature=abc,algorithm=HMAC-SHA256} → {@code abc}. */
    static @Nullable String signature(@Nullable String header) {
        if (header == null) {
            return null;
        }
        for (var part : header.split(",")) {
            var kv = part.strip().split("=", 2);
            if (kv.length == 2 && "signature".equalsIgnoreCase(kv[0].strip())) {
                return kv[1].strip();
            }
        }
        return null;
    }

    static Map<String, String> parseForm(String body) {
        var out = new HashMap<String, String>();
        for (var pair : body.split("&")) {
            var kv = pair.split("=", 2);
            if (kv.length == 2) {
                out.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode get(Credentials c, String path, Map<String, String> query) {
        return backoff.call(
                "Lightspeed " + c.account(),
                () -> CommerceHttp.authorized(
                        () -> api.get(CommerceHttp.uri(base(c.account()), path, query), "Bearer " + c.accessToken())));
    }
}
