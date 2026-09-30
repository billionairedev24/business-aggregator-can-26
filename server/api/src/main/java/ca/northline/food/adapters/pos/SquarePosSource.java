package ca.northline.food.adapters.pos;

import static ca.northline.shared.integration.ProviderHttp.text;

import ca.northline.food.application.PosMenuSource;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.integration.Backoff;
import ca.northline.shared.integration.ProviderHttp;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * A kitchen's menu in Square (S-36), with the same Square application as the S-35 catalogue sync (one redirect URL per
 * Square app — the shared callback hands the state to this module). Written from developer.squareup.com; never run
 * against Square.
 *
 * <ul>
 *   <li>OAuth: {@code /oauth2/authorize} with {@code ITEMS_READ MERCHANT_PROFILE_READ}, {@code /oauth2/token}; the
 *       30-day token is refreshed when less than 7 days remain; disconnect calls {@code /oauth2/revoke}.
 *   <li>Menu: {@code GET /v2/catalog/list?types=ITEM,CATEGORY,MODIFIER_LIST} (all pages). Category → section (no
 *       category → "Other"); item price = its cheapest variation; several variations become a required "pick 1"
 *       group priced as the difference to the cheapest; a variable-priced item has no price. Modifier lists → groups:
 *       SINGLE = up to 1, MULTIPLE = any; a minimum an item sets makes the group required.
 * </ul>
 */
class SquarePosSource implements PosMenuSource {

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

    private final PosProperties.Square config;
    private final Api api;
    private final Backoff backoff;
    private final Clock clock;

    SquarePosSource(PosProperties.Square config, Api api, Backoff backoff, Clock clock) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public PosProvider provider() {
        return PosProvider.SQUARE;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    private URI url(String path, Map<String, String> query) {
        return ProviderHttp.uri(config.baseUrl(), path, query);
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri) {
        var p = ProviderHttp.params();
        p.put("client_id", config.id());
        p.put("scope", config.scopes());
        p.put("session", "false");
        p.put("state", state);
        p.put("redirect_uri", redirectUri.toString());
        return url("/oauth2/authorize", p);
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri) {
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("client_secret", config.secret());
        body.put("code", params.getOrDefault("code", ""));
        body.put("grant_type", "authorization_code");
        body.put("redirect_uri", redirectUri.toString());
        var credentials = credentials(token(body), null);
        var merchant = credentials.account();
        var label = Optional.ofNullable(text(
                        call(
                                        credentials,
                                        () -> api.get(
                                                url("/v2/merchants/" + ProviderHttp.encode(merchant), Map.of()),
                                                config.apiVersion(),
                                                bearer(credentials)))
                                .path("merchant"),
                        "business_name"))
                .orElse("Square · " + merchant);
        return new Grant(credentials, merchant, label);
    }

    @Override
    public Grant link(String restaurantId) {
        throw new UnsupportedOperationException("Square connects with OAuth");
    }

    private JsonNode token(Map<String, Object> body) {
        return ProviderHttp.tokenCall(
                () -> backoff.call(
                        "Square token", () -> api.token(url("/oauth2/token", Map.of()), config.apiVersion(), body)),
                GrantRevoked::new);
    }

    private static Credentials credentials(JsonNode token, @Nullable Credentials previous) {
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
        var refresh = c.refreshToken();
        if (expires == null
                || refresh == null
                || expires.isAfter(clock.instant().plus(REFRESH_BEFORE))) {
            return c;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("client_secret", config.secret());
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refresh);
        return credentials(token(body), c);
    }

    @Override
    public PosMenu menu(Credentials c) {
        var objects = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var q = ProviderHttp.params();
            q.put("types", "ITEM,CATEGORY,MODIFIER_LIST");
            if (cursor != null) {
                q.put("cursor", cursor);
            }
            var page = call(c, () -> api.get(url("/v2/catalog/list", q), config.apiVersion(), bearer(c)));
            page.path("objects").forEach(objects::add);
            cursor = text(page, "cursor");
        } while (cursor != null);
        return menu(objects);
    }

    static PosMenu menu(List<JsonNode> objects) {
        var categories = new LinkedHashMap<String, String>();
        var lists = new LinkedHashMap<String, JsonNode>();
        var items = new ArrayList<JsonNode>();
        for (var o : objects) {
            if (o.path("is_deleted").asBoolean(false)) {
                continue;
            }
            var id = String.valueOf(text(o, "id"));
            switch (String.valueOf(text(o, "type"))) {
                case "CATEGORY" ->
                    categories.put(
                            id,
                            Optional.ofNullable(text(o.path("category_data"), "name"))
                                    .orElse("Menu"));
                case "MODIFIER_LIST" -> lists.put(id, o);
                case "ITEM" -> {
                    if (!o.path("item_data").path("is_archived").asBoolean(false)) {
                        items.add(o);
                    }
                }
                default -> {}
            }
        }
        // the minimum an item asks of a list makes the shared group required
        var minimums = new HashMap<String, Integer>();
        for (var item : items) {
            for (var info : item.path("item_data").path("modifier_list_info")) {
                var listId = text(info, "modifier_list_id");
                if (listId != null && info.path("enabled").asBoolean(true)) {
                    minimums.merge(
                            listId,
                            Math.max(0, info.path("min_selected_modifiers").asInt(0)),
                            Math::max);
                }
            }
        }
        var groups = new ArrayList<PosGroup>();
        lists.forEach((id, o) -> {
            var data = o.path("modifier_list_data");
            var options = new ArrayList<PosOption>();
            for (var m : data.path("modifiers")) {
                options.add(new PosOption(
                        String.valueOf(text(m, "id")),
                        Optional.ofNullable(text(m.path("modifier_data"), "name"))
                                .orElse("Option"),
                        m.path("modifier_data")
                                .path("price_money")
                                .path("amount")
                                .asLong(0)));
            }
            var single = "SINGLE".equals(text(data, "selection_type"));
            groups.add(new PosGroup(
                    id,
                    Optional.ofNullable(text(data, "name")).orElse("Options"),
                    minimums.getOrDefault(id, 0),
                    single ? Integer.valueOf(1) : null,
                    options));
        });
        var sections = new LinkedHashMap<String, List<PosItem>>();
        categories.keySet().forEach(k -> sections.put(k, new ArrayList<>()));
        for (var item : items) {
            var data = item.path("item_data");
            var id = String.valueOf(text(item, "id"));
            var category = Optional.ofNullable(text(data, "category_id"))
                    .or(() -> Optional.ofNullable(text(data.path("categories").path(0), "id")))
                    .filter(categories::containsKey)
                    .orElse("uncategorized");
            var variations = new ArrayList<PosOption>();
            Long cheapest = null;
            var variable = false;
            for (var v : data.path("variations")) {
                var vd = v.path("item_variation_data");
                if ("VARIABLE_PRICING".equals(text(vd, "pricing_type"))
                        || vd.path("price_money").isMissingNode()) {
                    variable = true;
                    continue;
                }
                var amount = vd.path("price_money").path("amount").asLong(0);
                cheapest = cheapest == null ? amount : Math.min(cheapest, amount);
                variations.add(new PosOption(
                        String.valueOf(text(v, "id")),
                        Optional.ofNullable(text(vd, "name")).orElse("Regular"),
                        amount));
            }
            var groupIds = new ArrayList<String>();
            if (variations.size() > 1 && cheapest != null) {
                var base = cheapest;
                var options = variations.stream()
                        .map(o -> new PosOption(o.externalId(), o.name(), o.priceDeltaCents() - base))
                        .toList();
                var variationGroup = new PosGroup(id + ":variations", "Size", 1, 1, options);
                groups.add(variationGroup);
                groupIds.add(variationGroup.externalId());
            }
            for (var info : data.path("modifier_list_info")) {
                var listId = text(info, "modifier_list_id");
                if (listId != null && info.path("enabled").asBoolean(true) && lists.containsKey(listId)) {
                    groupIds.add(listId);
                }
            }
            var price = variable && variations.isEmpty() ? null : cheapest;
            sections.computeIfAbsent(category, _ -> new ArrayList<>())
                    .add(new PosItem(
                            id,
                            Optional.ofNullable(text(data, "name")).orElse("Item"),
                            text(data, "description"),
                            price,
                            groupIds));
        }
        var out = new ArrayList<PosSection>();
        sections.forEach((id, list) -> {
            if (!list.isEmpty()) {
                out.add(new PosSection(id, categories.getOrDefault(id, "Other"), list));
            }
        });
        return new PosMenu(out, groups);
    }

    @Override
    public void revoke(Credentials c) {
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", config.id());
        body.put("access_token", c.accessToken());
        backoff.call(
                "Square revoke",
                () -> api.post(
                        url("/oauth2/revoke", Map.of()), config.apiVersion(), "Client " + config.secret(), body));
    }

    private static String bearer(Credentials c) {
        return "Bearer " + c.accessToken();
    }

    private JsonNode call(Credentials c, java.util.function.Supplier<JsonNode> call) {
        return backoff.call("Square " + c.account(), () -> ProviderHttp.authorized(call, GrantRevoked::new));
    }
}
