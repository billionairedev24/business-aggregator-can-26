package ca.northline.catalogue.adapters.commerce;

import static ca.northline.catalogue.adapters.commerce.CommerceHttp.JSON;
import static ca.northline.catalogue.adapters.commerce.CommerceHttp.text;

import ca.northline.catalogue.application.CommerceCatalogSource;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.integration.Backoff;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Shopify Admin GraphQL API (S-35), written from shopify.dev; never run against a real shop (no Partner account exists).
 *
 * <ul>
 *   <li><b>Install:</b> {@code https://{shop}/admin/oauth/authorize} (offline access token, scopes
 *       {@code read_products,read_inventory,write_inventory,read_locations} — the last two since S-158); the callback's {@code hmac} (HMAC-SHA256 hex of the other parameters,
 *       sorted, with the app secret) and {@code shop} are checked before {@code POST /admin/oauth/access_token}.
 *   <li><b>Reads:</b> {@code products(first: 50, query: "status:active")} with variants (SKU, barcode, price, total
 *       {@code inventoryQuantity}, inventory item) and up to 9 images. Cost-based throttling: a {@code THROTTLED}
 *       answer waits {@code (requested − available) / restoreRate} seconds and retries; when the bucket runs low the
 *       next call waits first.
 *   <li><b>Webhooks:</b> {@code webhookSubscriptionCreate} for products create/update/delete, inventory levels and app
 *       uninstall; deliveries carry {@code X-Shopify-Hmac-Sha256} (base64 HMAC-SHA256 of the raw body with the app
 *       secret) and are deduplicated on {@code X-Shopify-Event-Id}. The mandatory compliance topics are answered:
 *       Northline keeps no Shopify customer data, and {@code shop/redact} forgets the shop's links.
 *   <li><b>Two-way stock (S-158):</b> {@code locations} lists the shop's active locations; with one chosen, reads take
 *       that location's {@code available} quantity ({@code inventoryLevel(locationId:)}) instead of the total, and
 *       Northline's sales and restocks go out as {@code inventoryAdjustQuantities} (name {@code available}, reason
 *       {@code other} for a sale and {@code restock} for a cancellation or return, the Northline order as
 *       {@code referenceDocumentUri}) under the {@code @idempotent(key:)} directive with the push's own key, so a
 *       retried request is applied once. {@code ACCESS_DENIED} → the grant lacks {@code write_inventory}.
 * </ul>
 */
@Slf4j
class ShopifyCatalogSource implements CommerceCatalogSource {

    static final Pattern SHOP = Pattern.compile("^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$");
    static final List<String> TOPICS = List.of(
            "PRODUCTS_CREATE", "PRODUCTS_UPDATE", "PRODUCTS_DELETE", "INVENTORY_LEVELS_UPDATE", "APP_UNINSTALLED");

    private static final String PRODUCT_FIELDS = """
            fragment P on Product {
              id title descriptionHtml vendor status updatedAt
              media(first: 9) { nodes { ... on MediaImage { image { url } } } }
              variants(first: 100) {
                nodes { id sku barcode title price inventoryQuantity inventoryItem { id } selectedOptions { name value } }
              }
            }""";
    /** The same fields, with one location's {@code available} quantity (S-158). */
    private static final String PRODUCT_FIELDS_AT = PRODUCT_FIELDS.replace(
            "inventoryItem { id }",
            "inventoryItem { id inventoryLevel(locationId: $loc) { quantities(names: [\"available\"]) { name quantity } } }");

    static final String PRODUCTS_AT_QUERY = """
            query ProductsAt($cursor: String, $loc: ID!) {
              products(first: 50, after: $cursor, query: "status:active") {
                pageInfo { hasNextPage endCursor }
                nodes { ...P }
              }
            }
            """ + PRODUCT_FIELDS_AT;
    static final String PRODUCT_AT_QUERY = """
            query ProductAt($id: ID!, $loc: ID!) { product(id: $id) { ...P } }
            """ + PRODUCT_FIELDS_AT;
    static final String LOCATIONS_QUERY = """
            query Locations { locations(first: 50) { nodes { id name isActive } } }""";
    static final String ADJUST = """
            mutation Adjust($input: InventoryAdjustQuantitiesInput!, $key: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $key) {
                inventoryAdjustmentGroup { reason changes { name delta quantityAfterChange } }
                userErrors { field message code }
              }
            }""";
    static final String PRODUCTS_QUERY = """
            query Products($cursor: String) {
              products(first: 50, after: $cursor, query: "status:active") {
                pageInfo { hasNextPage endCursor }
                nodes { ...P }
              }
            }
            """ + PRODUCT_FIELDS;
    static final String PRODUCT_QUERY = """
            query Product($id: ID!) { product(id: $id) { ...P } }
            """ + PRODUCT_FIELDS;
    static final String SUBSCRIBE = """
            mutation Subscribe($topic: WebhookSubscriptionTopic!, $uri: String!) {
              webhookSubscriptionCreate(topic: $topic, webhookSubscription: { uri: $uri, format: JSON }) {
                webhookSubscription { id }
                userErrors { field message }
              }
            }""";

    interface Api {
        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode token(URI url, @RequestBody Map<String, String> body);

        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode graphql(
                URI url, @RequestHeader("X-Shopify-Access-Token") String token, @RequestBody Map<String, Object> body);

        @DeleteExchange
        void revoke(URI url, @RequestHeader("X-Shopify-Access-Token") String token);
    }

    private final CommerceProperties.Shopify config;
    private final Api api;
    private final Backoff backoff;

    ShopifyCatalogSource(CommerceProperties.Shopify config, Api api, Backoff backoff) {
        this.config = config;
        this.api = api;
        this.backoff = backoff;
    }

    @Override
    public CommerceProvider provider() {
        return CommerceProvider.SHOPIFY;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    private String base(String shop) {
        if (!SHOP.matcher(shop).matches()) {
            throw new Unverified("not a myshopify.com domain: " + shop);
        }
        return config.shopUrl().replace("{shop}", shop);
    }

    @Override
    public URI authorizationUrl(String state, URI redirectUri, @Nullable String shop) {
        if (shop == null) {
            throw new IllegalArgumentException("Shopify needs the shop");
        }
        var p = CommerceHttp.params();
        p.put("client_id", config.id());
        p.put("scope", config.scopes());
        p.put("redirect_uri", redirectUri.toString());
        p.put("state", state);
        return CommerceHttp.uri(base(shop), "/admin/oauth/authorize", p);
    }

    @Override
    public Grant exchange(Map<String, String> params, URI redirectUri, @Nullable String shop) {
        var hmac = params.get("hmac");
        var expected = HexFormat.of().formatHex(CommerceHttp.hmacSha256(config.secret(), signedQuery(params)));
        if (!CommerceHttp.same(hmac, expected)) {
            throw new Unverified("callback hmac");
        }
        var returned = params.get("shop");
        if (shop == null || !shop.equals(returned)) {
            throw new Unverified("callback for another shop");
        }
        var token = CommerceHttp.tokenCall(() -> api.token(
                CommerceHttp.uri(base(shop), "/admin/oauth/access_token", Map.of()),
                Map.of(
                        "client_id",
                        config.id(),
                        "client_secret",
                        config.secret(),
                        "code",
                        params.getOrDefault("code", ""))));
        var access = text(token, "access_token");
        if (access == null) {
            throw new IllegalStateException("Shopify returned no access token");
        }
        var scopes = Arrays.stream(String.valueOf(text(token, "scope")).split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty() && !"null".equals(s))
                .collect(Collectors.toSet());
        if (!scopes.contains("read_products")) {
            throw new Unverified("read_products not granted");
        }
        return new Grant(new Credentials(access, null, null, shop), shop, shop, scopes);
    }

    /** Shopify's callback signature input: every parameter except {@code hmac}/{@code signature}, sorted, {@code k=v&…}. */
    static byte[] signedQuery(Map<String, String> params) {
        var sorted = new TreeMap<>(params);
        sorted.remove("hmac");
        sorted.remove("signature");
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Offline access tokens don't expire. */
    @Override
    public Credentials refresh(Credentials credentials) {
        return credentials;
    }

    @Override
    public Page products(Credentials c, @Nullable String cursor) {
        return products(c, cursor, null);
    }

    @Override
    public Page products(Credentials c, @Nullable String cursor, @Nullable String locationId) {
        var vars = new LinkedHashMap<String, Object>();
        if (cursor != null) {
            vars.put("cursor", cursor);
        }
        if (locationId != null) {
            vars.put("loc", locationId);
        }
        var products = graphql(c, locationId == null ? PRODUCTS_QUERY : PRODUCTS_AT_QUERY, vars)
                .path("products");
        var items = new ArrayList<ExternalProduct>();
        for (var node : products.path("nodes")) {
            items.add(product(node, locationId != null));
        }
        var info = products.path("pageInfo");
        return new Page(items, info.path("hasNextPage").asBoolean(false) ? text(info, "endCursor") : null);
    }

    @Override
    public Optional<ExternalProduct> product(Credentials c, String externalId) {
        return product(c, externalId, null);
    }

    @Override
    public Optional<ExternalProduct> product(Credentials c, String externalId, @Nullable String locationId) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("id", externalId);
        if (locationId != null) {
            vars.put("loc", locationId);
        }
        var node = graphql(c, locationId == null ? PRODUCT_QUERY : PRODUCT_AT_QUERY, vars)
                .path("product");
        if (node.isMissingNode() || node.isNull() || !"ACTIVE".equals(text(node, "status"))) {
            return Optional.empty();
        }
        return Optional.of(product(node, locationId != null));
    }

    static ExternalProduct product(JsonNode n) {
        return product(n, false);
    }

    /** The {@code available} quantity at the queried location; 0 when the item isn't stocked there. */
    static int availableAt(JsonNode variant) {
        for (var q : variant.path("inventoryItem").path("inventoryLevel").path("quantities")) {
            if ("available".equals(text(q, "name"))) {
                return q.path("quantity").asInt(0);
            }
        }
        return 0;
    }

    static ExternalProduct product(JsonNode n, boolean atLocation) {
        var images = new ArrayList<URI>();
        for (var m : n.path("media").path("nodes")) {
            var url = text(m.path("image"), "url");
            if (url != null) {
                images.add(URI.create(url));
            }
        }
        var variants = new ArrayList<ExternalVariant>();
        for (var v : n.path("variants").path("nodes")) {
            var options = new LinkedHashMap<String, String>();
            for (var o : v.path("selectedOptions")) {
                var name = text(o, "name");
                var value = text(o, "value");
                // Shopify's single-variant products carry the placeholder option "Title: Default Title"
                if (name != null && value != null && !"Default Title".equals(value)) {
                    options.put(name, value);
                }
            }
            variants.add(new ExternalVariant(
                    String.valueOf(text(v, "id")),
                    text(v, "sku"),
                    text(v, "barcode"),
                    String.valueOf(text(v, "title")),
                    options,
                    CommerceHttp.cents(v.path("price")),
                    atLocation ? availableAt(v) : v.path("inventoryQuantity").asInt(0),
                    text(v.path("inventoryItem"), "id")));
        }
        var updated = text(n, "updatedAt");
        return new ExternalProduct(
                String.valueOf(text(n, "id")),
                String.valueOf(text(n, "title")),
                text(n, "descriptionHtml"),
                text(n, "vendor"),
                images,
                variants,
                updated == null ? null : Instant.parse(updated));
    }

    @Override
    public boolean subscribe(Credentials c, URI callbackUrl) {
        var ok = true;
        for (var topic : TOPICS) {
            var result = graphql(c, SUBSCRIBE, Map.of("topic", topic, "uri", callbackUrl.toString()))
                    .path("webhookSubscriptionCreate");
            for (var error : result.path("userErrors")) {
                var message = String.valueOf(text(error, "message"));
                if (!message.toLowerCase(java.util.Locale.ROOT).contains("already been taken")) {
                    log.warn("Shopify webhook {} for {} refused: {}", topic, c.account(), message);
                    ok = false;
                }
            }
        }
        return ok;
    }

    @Override
    public List<Location> locations(Credentials c) {
        var out = new ArrayList<Location>();
        for (var node : graphql(c, LOCATIONS_QUERY, Map.of()).path("locations").path("nodes")) {
            var id = text(node, "id");
            if (id != null && node.path("isActive").asBoolean(true)) {
                out.add(new Location(id, Objects.requireNonNullElse(text(node, "name"), id)));
            }
        }
        return out;
    }

    @Override
    public boolean canWriteStock(Set<String> grantedScopes) {
        return grantedScopes.contains("write_inventory");
    }

    @Override
    public Adjusted adjust(Credentials c, Adjustment a) {
        if (a.stockRef() == null) {
            return new Adjusted.Refused("not_found", "no inventory item for variant " + a.variantId());
        }
        var change = new LinkedHashMap<String, @Nullable Object>();
        change.put("delta", a.delta());
        change.put("inventoryItemId", a.stockRef());
        change.put("locationId", a.locationId());
        change.put("changeFromQuantity", null); // an adjustment, not a compare-and-set
        var input = new LinkedHashMap<String, Object>();
        input.put("reason", "sale".equals(a.reason()) ? "other" : "restock");
        input.put("name", "available");
        input.put("referenceDocumentUri", "gid://northline/" + a.reason() + "/" + a.sourceRef());
        input.put("changes", List.of(change));
        JsonNode result;
        try {
            result = graphql(c, ADJUST, Map.of("input", input, "key", a.idempotencyKey()))
                    .path("inventoryAdjustQuantities");
        } catch (AccessDenied e) {
            return new Adjusted.Refused("scope_missing", String.valueOf(e.getMessage()));
        }
        for (var error : result.path("userErrors")) {
            var code = String.valueOf(text(error, "code"));
            var message = String.valueOf(text(error, "message"));
            return new Adjusted.Refused(
                    code.contains("NOT_FOUND") || code.contains("NOT_STOCKED") ? "not_found" : "rejected",
                    code + ": " + message);
        }
        for (var ch : result.path("inventoryAdjustmentGroup").path("changes")) {
            if ("available".equals(text(ch, "name")) && ch.has("quantityAfterChange")) {
                return new Adjusted.Applied(ch.path("quantityAfterChange").asInt());
            }
        }
        return new Adjusted.Applied(null);
    }

    /** Shopify answers a missing scope as a GraphQL error {@code ACCESS_DENIED}. */
    static final class AccessDenied extends RuntimeException {
        AccessDenied(String message) {
            super(message);
        }
    }

    @Override
    public void revoke(Credentials c) {
        backoff.call("Shopify revoke", () -> {
            api.revoke(
                    CommerceHttp.uri(base(c.account()), "/admin/api_permissions/current.json", Map.of()),
                    c.accessToken());
            return true;
        });
    }

    @Override
    public Delivery verify(WebhookRequest request) {
        var expected = Base64.getEncoder().encodeToString(CommerceHttp.hmacSha256(config.secret(), request.body()));
        if (!config.configured() || !CommerceHttp.same(request.header("x-shopify-hmac-sha256"), expected)) {
            throw new Unverified("signature");
        }
        var shop = request.header("x-shopify-shop-domain");
        var topic = request.header("x-shopify-topic");
        var id = Optional.ofNullable(request.header("x-shopify-event-id"))
                .or(() -> Optional.ofNullable(request.header("x-shopify-webhook-id")))
                .orElse(null);
        if (shop == null || topic == null || id == null) {
            throw new Unverified("headers");
        }
        var body = JSON.readTree(request.body());
        List<Change> changes = switch (topic) {
            case "products/create", "products/update" ->
                Optional.ofNullable(text(body, "admin_graphql_api_id"))
                        .<List<Change>>map(gid -> List.of(new Change.ProductChanged(gid)))
                        .orElse(List.of());
            case "products/delete" ->
                Optional.ofNullable(text(body, "id"))
                        .<List<Change>>map(n -> List.of(new Change.ProductRemoved("gid://shopify/Product/" + n)))
                        .orElse(List.of());
            case "inventory_levels/update" ->
                Optional.ofNullable(text(body, "inventory_item_id"))
                        .<List<Change>>map(n -> List.of(new Change.StockChanged("gid://shopify/InventoryItem/" + n)))
                        .orElse(List.of());
            case "app/uninstalled" -> List.of(new Change.Uninstalled());
            case "shop/redact" -> List.of(new Change.Redact());
            default -> List.of(); // customers/data_request, customers/redact: no customer data is kept
        };
        return new Delivery(shop, id, changes);
    }

    // ── GraphQL with cost-based throttling ─────────────────────────────────────────────────────────────────────────

    private JsonNode graphql(Credentials c, String query, Map<String, ?> variables) {
        var url = CommerceHttp.uri(base(c.account()), "/admin/api/" + config.apiVersion() + "/graphql.json", Map.of());
        var body = Map.<String, Object>of("query", query, "variables", variables);
        for (int attempt = 0; ; attempt++) {
            var response = backoff.call(
                    "Shopify GraphQL", () -> CommerceHttp.authorized(() -> api.graphql(url, c.accessToken(), body)));
            var cost = response.path("extensions").path("cost");
            var errors = response.path("errors");
            if (throttled(errors)) {
                if (attempt >= backoff.maxRetries()) {
                    throw new IllegalStateException("Shopify kept throttling " + c.account());
                }
                backoff.pause(waitFor(cost));
                continue;
            }
            if (errors.isArray() && !errors.isEmpty()) {
                if ("ACCESS_DENIED".equals(text(errors.get(0).path("extensions"), "code"))) {
                    throw new AccessDenied("Shopify: " + text(errors.get(0), "message"));
                }
                throw new IllegalStateException("Shopify GraphQL error: " + text(errors.get(0), "message"));
            }
            var pace = waitFor(cost);
            if (!pace.isZero()) {
                backoff.pause(pace); // the bucket can't pay for another call of this cost yet
            }
            return response.path("data");
        }
    }

    static boolean throttled(JsonNode errors) {
        for (var e : errors) {
            if ("THROTTLED".equals(text(e.path("extensions"), "code"))) {
                return true;
            }
        }
        return false;
    }

    /** Seconds until the leaky bucket holds {@code requestedQueryCost} again: ⌈(requested − available) / restoreRate⌉. */
    static Duration waitFor(JsonNode cost) {
        var requested = cost.path("requestedQueryCost").asDouble(0);
        var status = cost.path("throttleStatus");
        var available = status.path("currentlyAvailable").asDouble(Double.MAX_VALUE);
        var restore = status.path("restoreRate").asDouble(50);
        var missing = requested - available;
        if (missing <= 0 || restore <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis((long) Math.ceil(missing / restore * 1000));
    }
}
