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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * A kitchen's menu in Clover (S-36), REST API v3, written from docs.clover.com; never run against Clover.
 *
 * <ul>
 *   <li>OAuth v2 (expiring tokens): {@code {authUrl}/oauth/v2/authorize}; the callback adds {@code merchant_id}
 *       (checked: letters and digits); {@code POST {apiUrl}/oauth/v2/token}, refreshed with {@code /oauth/v2/refresh}
 *       a minute before expiry. Clover has no revocation endpoint: disconnect destroys the tokens.
 *   <li>Menu: {@code /v3/merchants/{mId}/categories}, {@code /items?expand=categories,modifierGroups&filter=hidden=false}
 *       and {@code /modifier_groups?expand=modifiers}, 1000 a page ({@code offset}). Category → section (sort order),
 *       an item in several categories goes to the first, no category → "Other"; {@code priceType} VARIABLE → no
 *       price; modifier groups keep {@code minRequired} / {@code maxAllowed}. Clover items carry no description.
 * </ul>
 */
class CloverPosSource implements PosMenuSource {

    static final Pattern MERCHANT = Pattern.compile("^[A-Za-z0-9]{1,32}$");
    static final Duration REFRESH_BEFORE = Duration.ofMinutes(1);
    static final int PAGE = 1000;

    interface Api {
        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode token(URI url, @RequestBody Map<String, String> body);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode get(URI url, @RequestHeader("Authorization") String authorization);
    }

    private final PosProperties.Clover config;
    private final Api api;
    private final Backoff backoff;
    private final Clock clock;

    CloverPosSource(PosProperties.Clover config, Api api, Backoff backoff, Clock clock) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public PosProvider provider() {
        return PosProvider.CLOVER;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri) {
        var p = ProviderHttp.params();
        p.put("client_id", config.id());
        p.put("redirect_uri", redirectUri.toString());
        p.put("state", state);
        return ProviderHttp.uri(config.authUrl(), "/oauth/v2/authorize", p);
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri) {
        var merchant = params.getOrDefault("merchant_id", "");
        if (!MERCHANT.matcher(merchant).matches()) {
            throw new Unverified("merchant_id");
        }
        var token = token(
                "/oauth/v2/token",
                Map.of(
                        "client_id",
                        config.id(),
                        "client_secret",
                        config.secret(),
                        "code",
                        params.getOrDefault("code", "")));
        var credentials = credentials(token, merchant, null);
        var label = Optional.ofNullable(text(get(credentials, "/v3/merchants/" + merchant, Map.of()), "name"))
                .orElse("Clover · " + merchant);
        return new Grant(credentials, merchant, label);
    }

    @Override
    public Grant link(String restaurantId) {
        throw new UnsupportedOperationException("Clover connects with OAuth");
    }

    private JsonNode token(String path, Map<String, String> body) {
        return ProviderHttp.tokenCall(
                () -> backoff.call(
                        "Clover token", () -> api.token(ProviderHttp.uri(config.apiUrl(), path, Map.of()), body)),
                GrantRevoked::new);
    }

    private Credentials credentials(JsonNode token, String merchant, @Nullable String previousRefresh) {
        var access = text(token, "access_token");
        if (access == null) {
            throw new IllegalStateException("Clover returned no access token");
        }
        var expires = token.path("access_token_expiration").asLong(0);
        var refresh = Optional.ofNullable(text(token, "refresh_token")).orElse(previousRefresh);
        return new Credentials(
                access,
                refresh,
                expires > 0 ? Instant.ofEpochSecond(expires) : clock.instant().plusSeconds(1800),
                merchant);
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
        return credentials(
                token("/oauth/v2/refresh", Map.of("client_id", config.id(), "refresh_token", refresh)),
                c.account(),
                refresh);
    }

    @Override
    public PosMenu menu(Credentials c) {
        var base = "/v3/merchants/" + c.account();
        var categories = all(c, base + "/categories", Map.of());
        var items = all(c, base + "/items", Map.of("expand", "categories,modifierGroups", "filter", "hidden=false"));
        var groups = all(c, base + "/modifier_groups", Map.of("expand", "modifiers"));
        return menu(categories, items, groups);
    }

    static PosMenu menu(List<JsonNode> categories, List<JsonNode> items, List<JsonNode> groupRows) {
        var sorted = new ArrayList<>(categories);
        sorted.sort(java.util.Comparator.comparingInt(n -> n.path("sortOrder").asInt(0)));
        var sections = new LinkedHashMap<String, List<PosItem>>();
        var names = new LinkedHashMap<String, String>();
        sorted.forEach(cat -> {
            var id = String.valueOf(text(cat, "id"));
            names.put(id, Optional.ofNullable(text(cat, "name")).orElse("Menu"));
            sections.put(id, new ArrayList<>());
        });
        var groups = new ArrayList<PosGroup>();
        var known = new java.util.HashSet<String>();
        for (var g : groupRows) {
            var options = new ArrayList<PosOption>();
            for (var m : g.path("modifiers").path("elements")) {
                options.add(new PosOption(
                        String.valueOf(text(m, "id")),
                        Optional.ofNullable(text(m, "name")).orElse("Option"),
                        m.path("price").asLong(0)));
            }
            var max = g.path("maxAllowed");
            var id = String.valueOf(text(g, "id"));
            known.add(id);
            groups.add(new PosGroup(
                    id,
                    Optional.ofNullable(text(g, "name")).orElse("Options"),
                    Math.max(0, g.path("minRequired").asInt(0)),
                    max.isNumber() && max.asInt() > 0 ? Integer.valueOf(max.asInt()) : null,
                    options));
        }
        for (var it : items) {
            if (it.path("hidden").asBoolean(false) || !it.path("available").asBoolean(true)) {
                continue;
            }
            var category = text(it.path("categories").path("elements").path(0), "id");
            var section = category != null && sections.containsKey(category) ? category : "uncategorized";
            var groupIds = new ArrayList<String>();
            for (var g : it.path("modifierGroups").path("elements")) {
                var gid = text(g, "id");
                if (gid != null && known.contains(gid)) {
                    groupIds.add(gid);
                }
            }
            var variable =
                    !"FIXED".equals(Optional.ofNullable(text(it, "priceType")).orElse("FIXED"));
            sections.computeIfAbsent(section, _ -> new ArrayList<>())
                    .add(new PosItem(
                            String.valueOf(text(it, "id")),
                            Optional.ofNullable(text(it, "name")).orElse("Item"),
                            null,
                            variable ? null : Long.valueOf(it.path("price").asLong(0)),
                            groupIds));
        }
        var out = new ArrayList<PosSection>();
        sections.forEach((id, list) -> {
            if (!list.isEmpty()) {
                out.add(new PosSection(id, names.getOrDefault(id, "Other"), list));
            }
        });
        return new PosMenu(out, groups);
    }

    /** Every element of a v3 collection, 1000 a page. */
    private List<JsonNode> all(Credentials c, String path, Map<String, String> query) {
        var out = new ArrayList<JsonNode>();
        for (int offset = 0; ; offset += PAGE) {
            var q = new LinkedHashMap<>(query);
            q.put("limit", Integer.toString(PAGE));
            q.put("offset", Integer.toString(offset));
            var elements = get(c, path, q).path("elements");
            elements.forEach(out::add);
            if (elements.size() < PAGE) {
                return out;
            }
        }
    }

    private JsonNode get(Credentials c, String path, Map<String, String> query) {
        return backoff.call(
                "Clover " + c.account(),
                () -> ProviderHttp.authorized(
                        () -> api.get(ProviderHttp.uri(config.apiUrl(), path, query), "Bearer " + c.accessToken()),
                        GrantRevoked::new));
    }

    /** Clover has no endpoint to revoke a token: it is destroyed; the merchant uninstalls the app in the Clover App Market. */
    @Override
    public void revoke(Credentials credentials) {}
}
