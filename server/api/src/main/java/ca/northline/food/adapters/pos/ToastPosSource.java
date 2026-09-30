package ca.northline.food.adapters.pos;

import static ca.northline.shared.integration.ProviderHttp.text;

import ca.northline.food.application.PosMenuSource;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.integration.Backoff;
import ca.northline.shared.integration.ProviderHttp;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * A kitchen's menu in Toast (S-36). <b>Toast's API is partner-gated</b>: there is no merchant OAuth; Northline would
 * sign in with its own partner credentials (machine client) and a restaurant becomes readable once its owner turns on
 * the Northline integration in Toast. Written from doc.toasttab.com (menus API v2, authentication API) as last
 * published; <b>never run against Toast — Northline has no partner account yet</b> (docs/runbooks/pos-menu-import.md).
 *
 * <ul>
 *   <li>Sign-in: {@code POST /authentication/v1/authentication/login} ({@code clientId}, {@code clientSecret},
 *       {@code userAccessType: TOAST_MACHINE_CLIENT}) → {@code token.accessToken}, kept in memory until a minute before
 *       {@code expiresIn}.
 *   <li>Link: the kitchen gives its restaurant GUID; {@code GET /restaurants/v1/restaurants/{guid}} with
 *       {@code Toast-Restaurant-External-ID} must answer (else "turn on the Northline integration").
 *   <li>Menu: {@code GET /menus/v2/menus}. Menu groups (nested groups flattened) → sections, prefixed with the menu name
 *       when there are several menus; items with {@code price} (null = size/open pricing → can't be imported);
 *       {@code modifierGroupReferences} / {@code modifierOptionReferences} maps (by {@code referenceId}) → groups with
 *       {@code minSelections} / {@code maxSelections}, option price = what it adds.
 * </ul>
 */
class ToastPosSource implements PosMenuSource {

    interface Api {
        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode login(URI url, @RequestBody Map<String, String> body);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode get(
                URI url,
                @RequestHeader("Authorization") String authorization,
                @RequestHeader("Toast-Restaurant-External-ID") String restaurant);
    }

    private record Token(String value, Instant expiresAt) {}

    private final PosProperties.Toast config;
    private final Api api;
    private final Backoff backoff;
    private final Clock clock;
    private final AtomicReference<Token> token = new AtomicReference<>();

    ToastPosSource(PosProperties.Toast config, Api api, Backoff backoff, Clock clock) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public PosProvider provider() {
        return PosProvider.TOAST;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri) {
        throw new UnsupportedOperationException("Toast has no merchant OAuth (partner access)");
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri) {
        throw new UnsupportedOperationException("Toast has no merchant OAuth (partner access)");
    }

    @Override
    public Grant link(String restaurantId) {
        JsonNode restaurant;
        try {
            restaurant = get(restaurantId, "/restaurants/v1/restaurants/" + ProviderHttp.encode(restaurantId));
        } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.NotFound e) {
            throw new Unverified("restaurant not available to Northline: " + e.getStatusCode());
        }
        var name = Optional.ofNullable(text(restaurant.path("general"), "name"))
                .or(() -> Optional.ofNullable(text(restaurant, "restaurantName")))
                .orElse("Toast restaurant");
        return new Grant(new Credentials("", null, null, restaurantId), restaurantId, name);
    }

    @Override
    public Credentials refresh(Credentials credentials) {
        return credentials;
    }

    @Override
    public PosMenu menu(Credentials c) {
        return menu(get(c.account(), "/menus/v2/menus"));
    }

    static PosMenu menu(JsonNode body) {
        var groupRefs = body.path("modifierGroupReferences");
        var optionRefs = body.path("modifierOptionReferences");
        var groups = new ArrayList<PosGroup>();
        var byRef = new LinkedHashMap<String, String>(); // referenceId → guid
        for (var e : groupRefs.properties()) {
            var g = e.getValue();
            var guid = Optional.ofNullable(text(g, "guid")).orElse(e.getKey());
            byRef.put(e.getKey(), guid);
            var options = new ArrayList<PosOption>();
            for (var ref : g.path("modifierOptionReferences")) {
                var o = optionRefs.path(ref.asString());
                if (!o.isMissingNode()) {
                    options.add(new PosOption(
                            Optional.ofNullable(text(o, "guid")).orElse(ref.asString()),
                            Optional.ofNullable(text(o, "name")).orElse("Option"),
                            o.path("price").isNumber() ? ProviderHttp.cents(o.path("price")) : 0));
                }
            }
            var max = g.path("maxSelections");
            groups.add(new PosGroup(
                    guid,
                    Optional.ofNullable(text(g, "name")).orElse("Options"),
                    Math.max(0, g.path("minSelections").asInt(0)),
                    max.isNumber() && max.asInt() > 0 ? Integer.valueOf(max.asInt()) : null,
                    options));
        }
        var menus = body.path("menus");
        var prefix = menus.size() > 1;
        var sections = new ArrayList<PosSection>();
        for (var m : menus) {
            var menuName = Optional.ofNullable(text(m, "name")).orElse("Menu");
            for (var group : m.path("menuGroups")) {
                collect(group, prefix ? menuName + " · " : "", byRef, sections);
            }
        }
        return new PosMenu(sections, groups);
    }

    private static void collect(JsonNode group, String prefix, Map<String, String> byRef, List<PosSection> out) {
        var items = new ArrayList<PosItem>();
        for (var it : group.path("menuItems")) {
            var groupIds = new ArrayList<String>();
            for (var ref : it.path("modifierGroupReferences")) {
                var guid = byRef.get(ref.asString());
                if (guid != null) {
                    groupIds.add(guid);
                }
            }
            var price = it.path("price");
            items.add(new PosItem(
                    String.valueOf(text(it, "guid")),
                    Optional.ofNullable(text(it, "name")).orElse("Item"),
                    text(it, "description"),
                    price.isNumber() ? Long.valueOf(ProviderHttp.cents(price)) : null,
                    groupIds));
        }
        var name = Optional.ofNullable(text(group, "name")).orElse("Menu");
        if (!items.isEmpty()) {
            out.add(new PosSection(String.valueOf(text(group, "guid")), prefix + name, items));
        }
        for (var nested : group.path("menuGroups")) {
            collect(nested, prefix, byRef, out);
        }
    }

    /** Toast grants nothing to revoke: the owner turns the integration off in Toast. */
    @Override
    public void revoke(Credentials credentials) {}

    private JsonNode get(String restaurant, String path) {
        return backoff.call("Toast " + restaurant, () -> {
            try {
                return api.get(ProviderHttp.uri(config.apiUrl(), path, Map.of()), "Bearer " + token(false), restaurant);
            } catch (HttpClientErrorException.Unauthorized _) {
                return api.get(ProviderHttp.uri(config.apiUrl(), path, Map.of()), "Bearer " + token(true), restaurant);
            }
        });
    }

    private String token(boolean force) {
        var current = token.get();
        if (!force
                && current != null
                && current.expiresAt().isAfter(clock.instant().plusSeconds(60))) {
            return current.value();
        }
        var body = backoff.call(
                "Toast login",
                () -> api.login(
                        ProviderHttp.uri(config.apiUrl(), "/authentication/v1/authentication/login", Map.of()),
                        Map.of(
                                "clientId", config.id(),
                                "clientSecret", config.secret(),
                                "userAccessType", "TOAST_MACHINE_CLIENT")));
        var access = text(body.path("token"), "accessToken");
        if (access == null) {
            throw new IllegalStateException("Toast login returned no token");
        }
        var fresh = new Token(
                access,
                clock.instant().plusSeconds(body.path("token").path("expiresIn").asLong(3600)));
        token.set(fresh);
        return fresh.value();
    }
}
