package ca.northline.catalogue;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.awt.Color;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-35: the Shopify, Square and Lightspeed adapters end to end through the api, against WireMock stand-ins written from
 * each platform's documentation (docs/runbooks/commerce-sync.md). None has run against the real service: no accounts
 * exist. Ids, secrets and tokens are obviously fake. Covers the connect callback (Shopify's hmac, Lightspeed's
 * domain prefix), the initial import (throttling and 429 back-off included), incremental updates by webhook, a deleted
 * product becoming hidden, webhook signature checks and dedupe, token refresh with rotation, and a revoked grant.
 */
class CommerceSourcesWireMockTest extends CatalogueApiTest {

    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());
    static final String API = "https://api.test.northline.invalid";
    static final String SHOPIFY_SECRET = "fake-shopify-app-secret-for-tests";
    static final String SQUARE_KEY = "fake-square-signature-key-for-tests";
    static final String LIGHTSPEED_SECRET = "fake-lightspeed-client-secret-for-tests";
    static final String INTEGRATIONS = "/api/v1/merchants/{m}/listings/integrations";

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void platforms(DynamicPropertyRegistry r) {
        r.add("northline.commerce.provider", () -> "oauth");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        r.add("northline.commerce.api-url", () -> API);
        r.add("northline.commerce.studio-url", () -> "https://studio.test.northline.invalid");
        r.add("northline.commerce.max-backoff", () -> "PT0.01S");
        r.add("northline.commerce.images.hosts", () -> "localhost");
        r.add("northline.commerce.images.allow-http", () -> "true");
        r.add("northline.commerce.shopify.client-id", () -> "fake-shopify-client-id");
        r.add("northline.commerce.shopify.client-secret", () -> SHOPIFY_SECRET);
        r.add("northline.commerce.shopify.shop-url", () -> WM.baseUrl() + "/shopify/{shop}");
        r.add("northline.commerce.square.client-id", () -> "sq0idp-fake-client-id");
        r.add("northline.commerce.square.client-secret", () -> "sq0csp-fake-client-secret");
        r.add("northline.commerce.square.webhook-signature-key", () -> SQUARE_KEY);
        r.add("northline.commerce.square.base-url", () -> WM.baseUrl() + "/square");
        r.add("northline.commerce.lightspeed.client-id", () -> "fake-lightspeed-client-id");
        r.add("northline.commerce.lightspeed.client-secret", () -> LIGHTSPEED_SECRET);
        r.add("northline.commerce.lightspeed.auth-url", () -> WM.baseUrl() + "/ls-auth/connect");
        r.add("northline.commerce.lightspeed.api-url", () -> WM.baseUrl() + "/ls/{domain_prefix}");
    }

    @BeforeAll
    static void images() {
        WM.stubFor(get("/img/big.png")
                .willReturn(
                        aResponse().withHeader("Content-Type", "image/png").withBody(png(1200, 1200, Color.WHITE))));
        WM.stubFor(get("/img/small.png")
                .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(png(200, 200, Color.WHITE))));
    }

    @AfterAll
    static void stop() {
        WM.resetAll();
    }

    @Autowired
    SecretSealer sealer;

    // ── helpers ──────────────────────────────────────────────────────────────────

    Map<String, String> startConnect(Business biz, String provider, String body) throws Exception {
        var url = json(mvc.perform(postJson(INTEGRATIONS + "/" + provider + "/connect", body, biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isOk()))
                .path("authorizationUrl")
                .asString();
        var params = new TreeMap<String, String>(
                UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey, e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8))));
        params.put("_url", url);
        return params;
    }

    void callback(String provider, Map<String, String> params, String result, Business biz) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/v1/commerce/oauth/" + provider + "/callback");
        params.forEach(request::param);
        mvc.perform(request)
                .andExpect(status().isSeeOther())
                .andExpect(header().string(
                                "Location",
                                "https://studio.test.northline.invalid/b/" + biz.merchantId()
                                        + "/listings/bulk?commerce=" + provider + "&result=" + result));
    }

    void awaitSynced(Business biz, String provider) {
        await(() -> assertThat(jdbc.sql(
                                "select sync_status from catalogue.integrations where merchant_id = ? and provider = ?")
                        .params(biz.merchantId(), provider)
                        .query(String.class)
                        .optional())
                .contains("ok"));
    }

    String offerOf(Business biz, String provider, String externalId) {
        return jdbc.sql("""
                        select offer_id from catalogue.commerce_products
                        where merchant_id = ? and provider = ? and external_id = ?""")
                .params(biz.merchantId(), provider, externalId)
                .query(String.class)
                .single();
    }

    Object column(String offerId, String column) {
        return jdbc.sql("select " + column + " from catalogue.offers where id = ?")
                .params(offerId)
                .query((rs, _) -> rs.getObject(1))
                .single();
    }

    String integration(Business biz, String provider, String column) {
        return String.valueOf(
                jdbc.sql("select " + column + " from catalogue.integrations where merchant_id = ? and provider = ?")
                        .params(biz.merchantId(), provider)
                        .query((rs, _) -> rs.getObject(1))
                        .single());
    }

    /** The sealed credentials in clear (to check rotation). */
    String credentials(Business biz, String provider) {
        var id = integration(biz, provider, "id");
        var box = jdbc.sql(
                        "select token_ref, credentials_key, credentials_enc from catalogue.integrations where id = ?")
                .params(id)
                .query((rs, _) -> new SecretSealer.Sealed(rs.getString(1), rs.getBytes(2), rs.getBytes(3)))
                .single();
        return sealer.open(box, id);
    }

    static byte[] hmac(String key, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    org.springframework.test.web.servlet.ResultActions webhook(
            String provider, String body, Map<String, String> headers, String contentType) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/v1/webhooks/commerce/" + provider)
                .content(body)
                .contentType(contentType);
        headers.forEach(request::header);
        return mvc.perform(request);
    }

    // ── Shopify ──────────────────────────────────────────────────────────────────

    static String shopifyProduct(String id, String title, String status, String variants, String media) {
        return """
                {"id":"gid://shopify/Product/%s","title":"%s","descriptionHtml":"<p>Made for <b>Prairie</b> winters.</p>",
                 "vendor":"Northline Test","status":"%s","updatedAt":"2026-09-30T12:00:00Z",
                 "media":{"nodes":[%s]},"variants":{"nodes":[%s]}}""".formatted(id, title, status, media, variants);
    }

    static String shopifyVariant(String id, String sku, String price, int stock, String options) {
        return """
                {"id":"gid://shopify/ProductVariant/%s","sku":"%s","barcode":null,"title":"%s","price":"%s",
                 "inventoryQuantity":%d,"inventoryItem":{"id":"gid://shopify/InventoryItem/%s"},"selectedOptions":[%s]}""".formatted(id, sku, sku, price, stock, id, options);
    }

    static final String COST = """
            "extensions":{"cost":{"requestedQueryCost":52,"actualQueryCost":12,
              "throttleStatus":{"maximumAvailable":2000,"currentlyAvailable":1900,"restoreRate":100}}}""";

    @Test
    void shopify() throws Exception {
        var shop = "nl-" + Ids.next().toLowerCase(java.util.Locale.ROOT).substring(16) + ".myshopify.com";
        var base = "/shopify/" + shop;
        var graphql = base + "/admin/api/2026-07/graphql.json";
        var jacket = shopifyProduct(
                "501",
                "Insulated work jacket",
                "ACTIVE",
                shopifyVariant("5011", "JKT-M", "129.00", 4, "{\"name\":\"Size\",\"value\":\"M\"}") + ","
                        + shopifyVariant("5012", "JKT-L", "139.00", 2, "{\"name\":\"Size\",\"value\":\"L\"}"),
                "{\"image\":{\"url\":\"" + WM.baseUrl() + "/img/big.png\"}},{\"image\":{\"url\":\"" + WM.baseUrl()
                        + "/img/small.png\"}}");
        var gloves =
                shopifyProduct("502", "Leather gloves", "ACTIVE", shopifyVariant("5021", "GLV-1", "24.50", 10, ""), "");
        WM.stubFor(post(base + "/admin/oauth/access_token")
                .withRequestBody(matching(".*\"code\":\"fake-shopify-code\".*"))
                .willReturn(
                        okJson("{\"access_token\":\"shpat_fake_token\",\"scope\":\"read_products,read_inventory\"}")));
        WM.stubFor(
                post(graphql)
                        .withRequestBody(containing("webhookSubscriptionCreate"))
                        .willReturn(
                                okJson(
                                        "{\"data\":{\"webhookSubscriptionCreate\":{\"webhookSubscription\":{\"id\":\"gid://shopify/WebhookSubscription/1\"},\"userErrors\":[]}}}")));
        // first page: throttled once (cost-based), then served; second page after the cursor
        WM.stubFor(post(graphql)
                .inScenario("shopify-products")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(containing("query Products"))
                .willReturn(okJson("""
                        {"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}],
                         "extensions":{"cost":{"requestedQueryCost":52,"throttleStatus":{"maximumAvailable":2000,"currentlyAvailable":2,"restoreRate":100}}}}"""))
                .willSetStateTo("served"));
        WM.stubFor(post(graphql)
                .inScenario("shopify-products")
                .whenScenarioStateIs("served")
                .withRequestBody(containing("query Products"))
                .willReturn(okJson(
                        "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c1\"},\"nodes\":["
                                + jacket + "]}}," + COST + "}")));
        WM.stubFor(post(graphql)
                .atPriority(1)
                .withRequestBody(containing("\"cursor\":\"c1\""))
                .willReturn(okJson(
                        "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"c2\"},\"nodes\":["
                                + gloves + "]}}," + COST + "}")));

        var biz = seller(MerchantRole.OWNER);
        var consent = startConnect(biz, "shopify", "{\"shop\":\"" + shop.replace(".myshopify.com", "") + "\"}");
        assertThat(consent.get("_url")).startsWith(WM.baseUrl() + base + "/admin/oauth/authorize?");
        assertThat(consent)
                .containsEntry("client_id", "fake-shopify-client-id")
                .containsEntry("scope", "read_products,read_inventory")
                .containsEntry("redirect_uri", API + "/api/v1/commerce/oauth/shopify/callback");

        // a callback whose hmac doesn't verify is refused (and uses up the state)
        var forged = new TreeMap<String, String>();
        forged.put("code", "fake-shopify-code");
        forged.put("shop", shop);
        forged.put("state", consent.get("state"));
        forged.put("timestamp", "1790000000");
        forged.put("hmac", "00".repeat(32));
        callback("shopify", forged, "failed", biz);

        consent = startConnect(biz, "shopify", "{\"shop\":\"" + shop + "\"}");
        var params = new TreeMap<String, String>();
        params.put("code", "fake-shopify-code");
        params.put("host", "YWRtaW4uc2hvcGlmeS5jb20vc3RvcmUvbmwtdGVzdA");
        params.put("shop", shop);
        params.put("state", consent.get("state"));
        params.put("timestamp", "1790000000");
        var signed = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        params.put("hmac", HexFormat.of().formatHex(hmac(SHOPIFY_SECRET, signed)));
        callback("shopify", params, "connected", biz);
        awaitSynced(biz, "shopify");

        // webhooks registered on the api host (5 topics), catalogue imported
        WM.verify(
                5,
                postRequestedFor(urlPathEqualTo(graphql))
                        .withHeader("X-Shopify-Access-Token", equalTo("shpat_fake_token"))
                        .withRequestBody(containing(API + "/api/v1/webhooks/commerce/shopify")));
        assertThat(integration(biz, "shopify", "webhooks")).isEqualTo("active");
        assertThat(integration(biz, "shopify", "created_count")).isEqualTo("2");
        WM.verify(3, postRequestedFor(urlPathEqualTo(graphql)).withRequestBody(containing("query Products")));
        var jacketOffer = offerOf(biz, "shopify", "gid://shopify/Product/501");
        assertThat(column(jacketOffer, "vetting")).isEqualTo("draft");
        assertThat(column(jacketOffer, "variant_theme")).isEqualTo("size");
        assertThat(column(jacketOffer, "price_cents")).isEqualTo(12900L);
        assertThat(column(jacketOffer, "stock")).isEqualTo(6);
        assertThat(column(jacketOffer, "cardinality(own_images)")).isEqualTo(1); // the 200 px image is skipped
        var glovesOffer = offerOf(biz, "shopify", "gid://shopify/Product/502");
        assertThat(column(glovesOffer, "sku")).isEqualTo("GLV-1");
        assertThat(column(glovesOffer, "price_cents")).isEqualTo(2450L);

        // incremental: products/update names the product; only it is read again
        WM.stubFor(post(graphql)
                .withRequestBody(containing("query Product("))
                .withRequestBody(containing("gid://shopify/Product/502"))
                .willReturn(okJson("{\"data\":{\"product\":"
                        + shopifyProduct(
                                "502", "Leather gloves", "ACTIVE", shopifyVariant("5021", "GLV-1", "25.99", 3, ""), "")
                        + "}," + COST + "}")));
        var update = "{\"id\":502,\"admin_graphql_api_id\":\"gid://shopify/Product/502\",\"title\":\"Leather gloves\"}";
        var event = Ids.next();
        var headers = Map.of(
                "X-Shopify-Topic",
                "products/update",
                "X-Shopify-Shop-Domain",
                shop,
                "X-Shopify-Event-Id",
                event,
                "X-Shopify-Webhook-Id",
                Ids.next(),
                "X-Shopify-Hmac-Sha256",
                Base64.getEncoder().encodeToString(hmac(SHOPIFY_SECRET, update)));
        webhook(
                        "shopify",
                        update,
                        Map.of(
                                "X-Shopify-Topic",
                                "products/update",
                                "X-Shopify-Shop-Domain",
                                shop,
                                "X-Shopify-Event-Id",
                                Ids.next(),
                                "X-Shopify-Hmac-Sha256",
                                Base64.getEncoder().encodeToString(hmac("wrong-secret", update))),
                        "application/json")
                .andExpect(status().isForbidden());
        webhook("shopify", update, headers, "application/json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        await(() -> assertThat(column(glovesOffer, "price_cents")).isEqualTo(2599L));
        assertThat(column(glovesOffer, "stock")).isEqualTo(3);
        webhook("shopify", update, headers, "application/json")
                .andExpect(jsonPath("$.duplicate").value(true));

        // inventory_levels/update names the inventory item → its product is read again
        WM.stubFor(post(graphql)
                .withRequestBody(containing("query Product("))
                .withRequestBody(containing("gid://shopify/Product/502"))
                .willReturn(okJson("{\"data\":{\"product\":"
                        + shopifyProduct(
                                "502", "Leather gloves", "ACTIVE", shopifyVariant("5021", "GLV-1", "25.99", 1, ""), "")
                        + "}," + COST + "}")));
        var level = "{\"inventory_item_id\":5021,\"location_id\":1,\"available\":1}";
        webhook(
                        "shopify",
                        level,
                        Map.of(
                                "X-Shopify-Topic",
                                "inventory_levels/update",
                                "X-Shopify-Shop-Domain",
                                shop,
                                "X-Shopify-Event-Id",
                                Ids.next(),
                                "X-Shopify-Hmac-Sha256",
                                Base64.getEncoder().encodeToString(hmac(SHOPIFY_SECRET, level))),
                        "application/json")
                .andExpect(status().isOk());
        await(() -> assertThat(column(glovesOffer, "stock")).isEqualTo(1));

        // products/delete: the listing is hidden, never deleted
        jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                .params(jacketOffer)
                .update();
        var removed = "{\"id\":501}";
        webhook(
                        "shopify",
                        removed,
                        Map.of(
                                "X-Shopify-Topic",
                                "products/delete",
                                "X-Shopify-Shop-Domain",
                                shop,
                                "X-Shopify-Event-Id",
                                Ids.next(),
                                "X-Shopify-Hmac-Sha256",
                                Base64.getEncoder().encodeToString(hmac(SHOPIFY_SECRET, removed))),
                        "application/json")
                .andExpect(status().isOk());
        await(() -> assertThat(column(jacketOffer, "status")).isEqualTo("hidden"));
        assertThat(column(jacketOffer, "vetting")).isEqualTo("approved");

        // a full read that no longer lists the gloves hides them too
        WM.stubFor(post(graphql)
                .atPriority(1)
                .withRequestBody(containing("query Products"))
                .willReturn(okJson(
                        "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}," + COST + "}")));
        mvc.perform(MockMvcRequestBuilders.post(INTEGRATIONS + "/shopify/sync", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenCount").value(1))
                .andExpect(jsonPath("$.updates").value("webhooks"));
        assertThat(column(glovesOffer, "status")).isEqualTo("hidden");

        // disconnect revokes the app's access
        WM.stubFor(delete(base + "/admin/api_permissions/current.json")
                .willReturn(aResponse().withStatus(200)));
        mvc.perform(MockMvcRequestBuilders.post(INTEGRATIONS + "/shopify/disconnect", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.state").value("disconnected"));
        WM.verify(deleteRequestedFor(urlPathEqualTo(base + "/admin/api_permissions/current.json"))
                .withHeader("X-Shopify-Access-Token", equalTo("shpat_fake_token")));
    }

    // ── Square ───────────────────────────────────────────────────────────────────

    static String squareItem(String id, boolean archived, long price) {
        return """
                {"type":"ITEM","id":"%s","updated_at":"2026-09-30T12:00:00Z","is_deleted":false,
                 "item_data":{"name":"Snow brush %s","description":"Extendable, foam grip.","is_archived":%s,"image_ids":["IMG-%s"],
                  "variations":[{"type":"ITEM_VARIATION","id":"%s-V","item_variation_data":{"item_id":"%s","name":"Regular","sku":"SB-%s",
                   "price_money":{"amount":%d,"currency":"CAD"}}}]}}""".formatted(id, id, archived, id, id, id, id, price);
    }

    static String squareImage(String id) {
        return "{\"type\":\"IMAGE\",\"id\":\"IMG-" + id + "\",\"image_data\":{\"url\":\"" + WM.baseUrl()
                + "/img/big.png\"}}";
    }

    @Test
    void square() throws Exception {
        var merchant = "ML" + Ids.next();
        var item = "SQ" + Ids.next();
        var soon = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        WM.stubFor(post("/square/oauth2/token")
                .withRequestBody(matching(".*\"grant_type\":\"authorization_code\".*"))
                .withRequestBody(containing("sq-code-" + merchant))
                .willReturn(okJson("""
                        {"access_token":"EAAA-fake-%s","token_type":"bearer","expires_at":"%s","merchant_id":"%s",
                         "refresh_token":"fake-square-refresh"}""".formatted(merchant, soon, merchant))));
        // expires within 7 days → refreshed before the first read
        WM.stubFor(post("/square/oauth2/token")
                .withRequestBody(matching(".*\"grant_type\":\"refresh_token\".*"))
                .willReturn(okJson("""
                        {"access_token":"EAAA-refreshed-%s","expires_at":"%s","merchant_id":"%s"}""".formatted(
                        merchant, Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS), merchant))));
        WM.stubFor(get("/square/v2/merchants/" + merchant)
                .willReturn(okJson(
                        "{\"merchant\":{\"id\":\"" + merchant + "\",\"business_name\":\"Prairie Wrench Parts\"}}")));
        WM.stubFor(post("/square/v2/catalog/search")
                .inScenario("square-" + merchant)
                .whenScenarioStateIs(STARTED)
                .withHeader("Authorization", equalTo("Bearer EAAA-refreshed-" + merchant))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "0")
                        .withBody("{\"errors\":[{\"code\":\"RATE_LIMITED\"}]}"))
                .willSetStateTo("open"));
        WM.stubFor(post("/square/v2/catalog/search")
                .inScenario("square-" + merchant)
                .whenScenarioStateIs("open")
                .withHeader("Authorization", equalTo("Bearer EAAA-refreshed-" + merchant))
                .willReturn(okJson("{\"objects\":[" + squareItem(item, false, 1899) + ","
                        + squareItem(item + "X", true, 500) + "],\"related_objects\":[" + squareImage(item) + "]}")));
        WM.stubFor(post("/square/v2/inventory/counts/batch-retrieve")
                .withRequestBody(containing(item + "-V"))
                .willReturn(okJson("""
                        {"counts":[{"catalog_object_id":"%s-V","state":"IN_STOCK","location_id":"L1","quantity":"7"},
                                   {"catalog_object_id":"%s-V","state":"IN_STOCK","location_id":"L2","quantity":"2.5"}]}""".formatted(item, item))));

        var biz = seller(MerchantRole.OWNER);
        var consent = startConnect(biz, "square", "{}");
        assertThat(consent.get("_url")).startsWith(WM.baseUrl() + "/square/oauth2/authorize?");
        assertThat(consent)
                .containsEntry("scope", "ITEMS_READ INVENTORY_READ MERCHANT_PROFILE_READ")
                .containsEntry("session", "false")
                .containsEntry("redirect_uri", API + "/api/v1/commerce/oauth/square/callback");
        callback("square", Map.of("code", "sq-code-" + merchant, "state", consent.get("state")), "connected", biz);
        awaitSynced(biz, "square");

        assertThat(integration(biz, "square", "account_label")).isEqualTo("Prairie Wrench Parts");
        assertThat(integration(biz, "square", "webhooks")).isEqualTo("active"); // app-level subscription, key set
        assertThat(credentials(biz, "square"))
                .contains("EAAA-refreshed-" + merchant)
                .contains("fake-square-refresh");
        var offer = offerOf(biz, "square", item);
        assertThat(column(offer, "price_cents")).isEqualTo(1899L);
        assertThat(column(offer, "stock")).isEqualTo(9); // 7 + 2 (partial units are dropped)
        assertThat(column(offer, "cardinality(own_images)")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from catalogue.commerce_products where merchant_id = ?")
                        .params(biz.merchantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1); // the archived item is not imported

        // inventory.count.updated → the item is read again
        WM.stubFor(get(urlPathEqualTo("/square/v2/catalog/object/" + item))
                .willReturn(okJson("{\"object\":" + squareItem(item, false, 1899) + ",\"related_objects\":["
                        + squareImage(item) + "]}")));
        WM.stubFor(post("/square/v2/inventory/counts/batch-retrieve")
                .withRequestBody(containing(item + "-V"))
                .willReturn(okJson("{\"counts\":[{\"catalog_object_id\":\"" + item
                        + "-V\",\"state\":\"IN_STOCK\",\"quantity\":\"4\"}]}")));
        var body = """
                {"merchant_id":"%s","type":"inventory.count.updated","event_id":"%s","created_at":"2026-09-30T12:00:00Z",
                 "data":{"type":"inventory","id":"x","object":{"inventory_counts":[{"catalog_object_id":"%s-V","quantity":"4","state":"IN_STOCK"}]}}}""".formatted(merchant, Ids.next(), item);
        var signature =
                Base64.getEncoder().encodeToString(hmac(SQUARE_KEY, API + "/api/v1/webhooks/commerce/square" + body));
        webhook(
                        "square",
                        body,
                        // signed without the notification URL: refused
                        Map.of(
                                "x-square-hmacsha256-signature",
                                Base64.getEncoder().encodeToString(hmac(SQUARE_KEY, body))),
                        "application/json")
                .andExpect(status().isForbidden());
        webhook("square", body, Map.of("x-square-hmacsha256-signature", signature), "application/json")
                .andExpect(status().isOk());
        await(() -> assertThat(column(offer, "stock")).isEqualTo(4));

        // catalog.version.updated doesn't say what changed → full read; the item is archived now → hidden
        WM.stubFor(post("/square/v2/catalog/search")
                .atPriority(1)
                .willReturn(okJson("{\"objects\":[" + squareItem(item, true, 1899) + "]}")));
        jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                .params(offer)
                .update();
        var changed = """
                {"merchant_id":"%s","type":"catalog.version.updated","event_id":"%s",
                 "data":{"type":"catalog","id":"","object":{"catalog_version":{"updated_at":"2026-09-30T12:05:00Z"}}}}""".formatted(merchant, Ids.next());
        webhook(
                        "square",
                        changed,
                        Map.of(
                                "x-square-hmacsha256-signature",
                                Base64.getEncoder()
                                        .encodeToString(
                                                hmac(SQUARE_KEY, API + "/api/v1/webhooks/commerce/square" + changed))),
                        "application/json")
                .andExpect(status().isOk());
        await(() -> assertThat(column(offer, "status")).isEqualTo("hidden"));

        // disconnect calls /oauth2/revoke with the app's secret
        WM.stubFor(post("/square/oauth2/revoke").willReturn(okJson("{\"success\":true}")));
        mvc.perform(MockMvcRequestBuilders.post(INTEGRATIONS + "/square/disconnect", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.state").value("disconnected"));
        WM.verify(postRequestedFor(urlPathEqualTo("/square/oauth2/revoke"))
                .withHeader("Authorization", equalTo("Client sq0csp-fake-client-secret"))
                .withRequestBody(containing("EAAA-refreshed-" + merchant)));
    }

    // ── Lightspeed ───────────────────────────────────────────────────────────────

    @Test
    void lightspeed() throws Exception {
        var prefix = "nl" + Ids.next().toLowerCase(java.util.Locale.ROOT).substring(18);
        var base = "/ls/" + prefix;
        var parent = "p-" + prefix;
        var single = "s-" + prefix;
        var soon = Instant.now().plusSeconds(60).getEpochSecond();
        WM.stubFor(post(base + "/api/1.0/token")
                .withRequestBody(containing("grant_type=authorization_code"))
                .willReturn(okJson("""
                        {"access_token":"ls-access-1","token_type":"Bearer","expires":%d,"expires_in":60,
                         "refresh_token":"ls-refresh-1","domain_prefix":"%s"}""".formatted(soon, prefix))));
        WM.stubFor(post(base + "/api/1.0/token")
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=ls-refresh-1"))
                .willReturn(okJson("""
                        {"access_token":"ls-access-2","expires":%d,"refresh_token":"ls-refresh-2"}""".formatted(Instant.now().plusSeconds(3600).getEpochSecond()))));
        WM.stubFor(post(base + "/api/webhooks").willReturn(okJson("{\"id\":\"wh\"}")));
        var rows = """
                {"data":[
                  {"id":"%1$s","name":"Tire chains","description":"<p>Quick-fit</p>","has_variants":true,"is_active":true,
                   "images":[{"sizes":{"original":"%3$s/img/big.png"}}],"version":3},
                  {"id":"%1$s-15","name":"Tire chains","variant_parent_id":"%1$s","variant_name":"15 in","sku":"CH-15",
                   "variant_options":[{"name":"Size","value":"15 in"}],"price_excluding_tax":119.99,"is_active":true,"version":4},
                  {"id":"%1$s-16","name":"Tire chains","variant_parent_id":"%1$s","variant_name":"16 in","sku":"CH-16",
                   "variant_options":[{"name":"Size","value":"16 in"}],"price_excluding_tax":129.99,"is_active":true,"version":5},
                  {"id":"%2$s","name":"Booster pack","sku":"BST-1","price_excluding_tax":149.5,"is_active":true,
                   "product_codes":[{"type":"UPC","code":"062782110490"}],"version":6},
                  {"id":"%2$s-old","name":"Old stock","sku":"OLD-1","price_excluding_tax":5,"is_active":false,"version":7}
                ],"version":{"min":3,"max":7}}""".formatted(parent, single, WM.baseUrl());
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products"))
                .inScenario("ls-" + prefix)
                .whenScenarioStateIs(STARTED)
                .withQueryParam("after", equalTo("0"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", Instant.now().minusSeconds(1).toString()))
                .willSetStateTo("open"));
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products"))
                .inScenario("ls-" + prefix)
                .whenScenarioStateIs("open")
                .withQueryParam("after", equalTo("0"))
                .withHeader("Authorization", equalTo("Bearer ls-access-2"))
                .willReturn(okJson(rows)));
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products"))
                .withQueryParam("after", equalTo("7"))
                .willReturn(okJson("{\"data\":[],\"version\":{\"min\":null,\"max\":null}}")));
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/inventory"))
                .withQueryParam("after", equalTo("0"))
                .willReturn(okJson("""
                        {"data":[{"product_id":"%1$s-15","outlet_id":"o1","inventory_level":2},
                                 {"product_id":"%1$s-15","outlet_id":"o2","inventory_level":1},
                                 {"product_id":"%2$s","outlet_id":"o1","inventory_level":5}],"version":{"max":9}}""".formatted(parent, single))));
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/inventory"))
                .withQueryParam("after", equalTo("9"))
                .willReturn(okJson("{\"data\":[]}")));

        var biz = seller(MerchantRole.OWNER);
        var consent = startConnect(biz, "lightspeed", "{}");
        assertThat(consent.get("_url")).startsWith(WM.baseUrl() + "/ls-auth/connect?response_type=code&");
        // a domain prefix that isn't a store name never becomes a host
        callback(
                "lightspeed",
                Map.of("code", "c", "state", consent.get("state"), "domain_prefix", "evil.example.com/x"),
                "failed",
                biz);
        consent = startConnect(biz, "lightspeed", "{}");
        callback(
                "lightspeed",
                Map.of("code", "ls-code", "state", consent.get("state"), "domain_prefix", prefix),
                "connected",
                biz);
        awaitSynced(biz, "lightspeed");

        // the token expired within 5 minutes → refreshed, and the rotated refresh token re-sealed
        assertThat(credentials(biz, "lightspeed")).contains("ls-access-2").contains("ls-refresh-2");
        WM.verify(
                2,
                postRequestedFor(urlPathEqualTo(base + "/api/webhooks"))
                        .withHeader("Authorization", equalTo("Bearer ls-access-2"))
                        .withRequestBody(containing(URLEncoder.encode(
                                API + "/api/v1/webhooks/commerce/lightspeed", StandardCharsets.UTF_8))));
        var chains = offerOf(biz, "lightspeed", parent);
        assertThat(column(chains, "variant_theme")).isEqualTo("size");
        assertThat(column(chains, "price_cents")).isEqualTo(11999L);
        assertThat(column(chains, "stock")).isEqualTo(3);
        var booster = offerOf(biz, "lightspeed", single);
        assertThat(column(booster, "price_cents")).isEqualTo(14950L);
        assertThat(column(booster, "stock")).isEqualTo(5);

        // product.update (form post, X-Signature over the raw body) → the product is read again
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products/" + single))
                .willReturn(okJson("{\"data\":{\"id\":\"" + single + "\",\"name\":\"Booster pack\",\"sku\":\"BST-1\","
                        + "\"price_excluding_tax\":139.0,\"is_active\":true}}")));
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products/" + single + "/inventory"))
                .willReturn(okJson("{\"data\":[{\"outlet_id\":\"o1\",\"inventory_level\":4}]}")));
        var payload = "{\"id\":\"" + single + "\",\"name\":\"Booster pack\"}";
        var form = "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)
                + "&type=product.update&domain_prefix=" + prefix + "&retailer_id=r1";
        var sig = "signature=" + HexFormat.of().formatHex(hmac(LIGHTSPEED_SECRET, form)) + ", algorithm=HMAC-SHA256";
        webhook(
                        "lightspeed",
                        form,
                        Map.of("X-Signature", "signature=bad, algorithm=HMAC-SHA256"),
                        "application/x-www-form-urlencoded")
                .andExpect(status().isForbidden());
        webhook("lightspeed", form, Map.of("X-Signature", sig), "application/x-www-form-urlencoded")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        await(() -> assertThat(column(booster, "price_cents")).isEqualTo(13900L));
        assertThat(column(booster, "stock")).isEqualTo(4);
        webhook("lightspeed", form, Map.of("X-Signature", sig), "application/x-www-form-urlencoded")
                .andExpect(jsonPath("$.duplicate").value(true));

        // the merchant removes the add-on: the API answers 401 → the connection needs a reconnect
        WM.stubFor(get(urlPathEqualTo(base + "/api/2.0/products"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(401)));
        mvc.perform(MockMvcRequestBuilders.post(INTEGRATIONS + "/lightspeed/sync", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("reconnect"));
        assertThat(integration(biz, "lightspeed", "last_error")).contains("401");
    }
}
