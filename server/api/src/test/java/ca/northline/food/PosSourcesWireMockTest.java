package ca.northline.food;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.KitchenFixtures.Kitchen;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-36: the Square, Clover and Toast menu adapters end to end through the api, against WireMock stand-ins written from
 * each POS's documentation (docs/runbooks/pos-menu-import.md). None has run against the real service (Toast's API is
 * partner-gated; no account of any of the three exists). Ids, secrets and tokens are obviously fake.
 */
class PosSourcesWireMockTest extends IntegrationTest {

    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());
    static final String API = "https://api.test.northline.invalid";
    static final String STUDIO = "https://studio.test.northline.invalid";

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void pos(DynamicPropertyRegistry r) {
        r.add("northline.pos.provider", () -> "oauth");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        r.add("northline.pos.api-url", () -> API);
        r.add("northline.pos.studio-url", () -> STUDIO);
        r.add("northline.pos.max-backoff", () -> "PT0.01S");
        r.add("northline.pos.square.client-id", () -> "sq0idp-fake-kitchen-client");
        r.add("northline.pos.square.client-secret", () -> "sq0csp-fake-kitchen-secret");
        r.add("northline.pos.square.base-url", () -> WM.baseUrl() + "/square");
        r.add("northline.pos.clover.client-id", () -> "FAKECLOVERAPPID");
        r.add("northline.pos.clover.client-secret", () -> "fake-clover-app-secret");
        r.add("northline.pos.clover.auth-url", () -> WM.baseUrl() + "/clover-www");
        r.add("northline.pos.clover.api-url", () -> WM.baseUrl() + "/clover-api");
        r.add("northline.pos.toast.client-id", () -> "fake-toast-partner-client");
        r.add("northline.pos.toast.client-secret", () -> "fake-toast-partner-secret");
        r.add("northline.pos.toast.api-url", () -> WM.baseUrl() + "/toast");
    }

    @AfterAll
    static void stop() {
        WM.resetAll();
    }

    @Autowired
    JdbcClient jdbc;

    KitchenFixtures fx() {
        return new KitchenFixtures(jdbc, data);
    }

    ResultActions postJson(String path, String body, String user) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.member(user)));
    }

    Map<String, String> consent(Kitchen k, String pos, String menuId) throws Exception {
        String url = JsonPath.read(
                postJson(k.base() + "/pos/" + pos + "/connect", "{\"menuId\":\"" + menuId + "\"}", k.ownerId())
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.authorizationUrl");
        var params =
                UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey, e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
        var out = new java.util.HashMap<>(params);
        out.put("_url", url);
        return out;
    }

    void callback(String pos, Map<String, String> params, String expected) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/v1/commerce/oauth/" + pos + "/callback");
        params.forEach(request::param);
        mvc.perform(request).andExpect(status().isSeeOther()).andExpect(header().string("Location", expected));
    }

    String previewAndApply(Kitchen k, String menuId, String pos, int expectedNew) throws Exception {
        String id = JsonPath.read(
                postJson(k.base() + "/menus/" + menuId + "/pos-imports", "{\"provider\":\"" + pos + "\"}", k.ownerId())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.diff.counts.newItems").value(expectedNew))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        postJson(k.base() + "/pos-imports/" + id + "/apply", "{}", k.ownerId()).andExpect(status().isOk());
        return id;
    }

    List<String> items(String menuId) {
        return jdbc.sql("""
                        select s.name || '/' || i.name || '/' || i.price_cents || '/' || (i.allergens is null)
                        from food.menu_items i join food.menu_sections s on s.id = i.section_id
                        where s.menu_id = ? order by s.sort, i.sort""").params(menuId).query(String.class).list();
    }

    List<String> groups(String merchantId) {
        return jdbc.sql("""
                        select g.name || ':' || g.pick_rule || ':' || g.pick_count || ':' || coalesce(g.required, false) || ':'
                               || (select string_agg(o.name || '+' || o.price_delta_cents, ',' order by o.sort)
                                   from food.modifier_options o where o.group_id = g.id)
                        from food.modifier_groups g where g.merchant_id = ? order by g.sort""").params(merchantId).query(String.class).list();
    }

    // ── Square ───────────────────────────────────────────────────────────────────

    @Test
    void square() throws Exception {
        var merchant = "MLK" + Ids.next();
        var soon = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        WM.stubFor(post("/square/oauth2/token")
                .withRequestBody(containing("\"grant_type\":\"authorization_code\""))
                .withRequestBody(containing("sq-kitchen-code-" + merchant))
                .willReturn(okJson("""
                        {"access_token":"EAAA-kitchen-%s","expires_at":"%s","merchant_id":"%s","refresh_token":"fake-sq-kitchen-refresh"}""".formatted(merchant, soon, merchant))));
        WM.stubFor(post("/square/oauth2/token")
                .withRequestBody(containing("\"grant_type\":\"refresh_token\""))
                .withRequestBody(containing("fake-sq-kitchen-refresh"))
                .willReturn(okJson("""
                        {"access_token":"EAAA-kitchen-refreshed-%s","expires_at":"%s","merchant_id":"%s"}""".formatted(
                        merchant, Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS), merchant))));
        WM.stubFor(get("/square/v2/merchants/" + merchant)
                .willReturn(okJson("{\"merchant\":{\"business_name\":\"Pho Dau Bo\"}}")));
        WM.stubFor(get(urlPathEqualTo("/square/v2/catalog/list"))
                .withHeader("Authorization", equalTo("Bearer EAAA-kitchen-refreshed-" + merchant))
                .withQueryParam("types", equalTo("ITEM,CATEGORY,MODIFIER_LIST"))
                .willReturn(okJson("""
                        {"objects":[
                          {"type":"CATEGORY","id":"C-PHO","category_data":{"name":"Pho"}},
                          {"type":"MODIFIER_LIST","id":"ML-EXTRAS","modifier_list_data":{"name":"Extras","selection_type":"MULTIPLE",
                            "modifiers":[{"id":"M-NOODLE","modifier_data":{"name":"Extra noodles","price_money":{"amount":250,"currency":"CAD"}}},
                                         {"id":"M-BEEF","modifier_data":{"name":"Extra beef","price_money":{"amount":400,"currency":"CAD"}}}]}},
                          {"type":"ITEM","id":"I-PHO","item_data":{"name":"Pho tai","description":"Rare beef","category_id":"C-PHO",
                            "modifier_list_info":[{"modifier_list_id":"ML-EXTRAS","min_selected_modifiers":-1,"max_selected_modifiers":-1,"enabled":true}],
                            "variations":[
                              {"type":"ITEM_VARIATION","id":"V-REG","item_variation_data":{"name":"Regular","pricing_type":"FIXED_PRICING","price_money":{"amount":1695,"currency":"CAD"}}},
                              {"type":"ITEM_VARIATION","id":"V-LRG","item_variation_data":{"name":"Large","pricing_type":"FIXED_PRICING","price_money":{"amount":1995,"currency":"CAD"}}}]}},
                          {"type":"ITEM","id":"I-SOUP","item_data":{"name":"Soup of the day","category_id":"C-PHO",
                            "variations":[{"type":"ITEM_VARIATION","id":"V-SOUP","item_variation_data":{"name":"Bowl","pricing_type":"VARIABLE_PRICING"}}]}},
                          {"type":"ITEM","id":"I-OLD","item_data":{"name":"Old dish","is_archived":true,"category_id":"C-PHO",
                            "variations":[{"type":"ITEM_VARIATION","id":"V-OLD","item_variation_data":{"price_money":{"amount":100}}}]}},
                          {"type":"ITEM","id":"I-TEA","item_data":{"name":"Jasmine tea",
                            "variations":[{"type":"ITEM_VARIATION","id":"V-TEA","item_variation_data":{"name":"Pot","price_money":{"amount":450}}}]}}
                        ],"cursor":"page-2"}""")));
        WM.stubFor(get(urlPathEqualTo("/square/v2/catalog/list"))
                .atPriority(1)
                .withQueryParam("cursor", equalTo("page-2"))
                .willReturn(okJson("{\"objects\":[]}")));

        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        var consent = consent(k, "square", menu.menuId());
        assertThat(consent.get("_url")).startsWith(WM.baseUrl() + "/square/oauth2/authorize?");
        assertThat(consent)
                .containsEntry("scope", "ITEMS_READ MERCHANT_PROFILE_READ")
                .containsEntry("redirect_uri", API + "/api/v1/commerce/oauth/square/callback");
        callback(
                "square",
                Map.of("code", "sq-kitchen-code-" + merchant, "state", consent.get("state")),
                STUDIO + "/b/" + k.merchantId() + "/kitchen/menu?pos=square&result=connected&menu=" + menu.menuId());

        previewAndApply(k, menu.menuId(), "square", 2);
        assertThat(items(menu.menuId())).containsExactly("Pho/Pho tai/1695/true", "Other/Jasmine tea/450/true");
        assertThat(groups(k.merchantId()))
                .containsExactlyInAnyOrder(
                        "Extras:up_to:2:false:Extra noodles+250,Extra beef+400",
                        "Size:exactly:1:true:Regular+0,Large+300");
        // the 2-day token was refreshed before the read
        WM.verify(
                postRequestedFor(urlPathEqualTo("/square/oauth2/token")).withRequestBody(containing("refresh_token")));
    }

    // ── Clover ───────────────────────────────────────────────────────────────────

    @Test
    void clover() throws Exception {
        var merchant = ("CLV" + Ids.next().substring(10)).toUpperCase(Locale.ROOT);
        var base = "/clover-api/v3/merchants/" + merchant;
        WM.stubFor(post("/clover-api/oauth/v2/token")
                .withRequestBody(containing("clover-code-" + merchant))
                .willReturn(okJson("""
                        {"access_token":"clover-access-1-%s","access_token_expiration":%d,"refresh_token":"clover-refresh-1","refresh_token_expiration":%d}""".formatted(
                                merchant,
                                Instant.now().plusSeconds(30).getEpochSecond(),
                                Instant.now().plusSeconds(86400).getEpochSecond()))));
        WM.stubFor(post("/clover-api/oauth/v2/refresh")
                .withRequestBody(containing("clover-refresh-1"))
                .willReturn(okJson(
                        """
                        {"access_token":"clover-access-2-%s","access_token_expiration":%d,"refresh_token":"clover-refresh-2"}""".formatted(merchant, Instant.now().plusSeconds(1800).getEpochSecond()))));
        WM.stubFor(get(urlPathEqualTo(base))
                .willReturn(okJson("{\"id\":\"" + merchant + "\",\"name\":\"Pho Dau Bo Clover\"}")));
        WM.stubFor(get(urlPathEqualTo(base + "/categories"))
                .withHeader("Authorization", equalTo("Bearer clover-access-2-" + merchant))
                .willReturn(okJson("""
                        {"elements":[{"id":"CAT2","name":"Drinks","sortOrder":2},{"id":"CAT1","name":"Banh mi","sortOrder":1}]}""")));
        // first page rate-limited once
        WM.stubFor(get(urlPathEqualTo(base + "/items"))
                .inScenario("clover-" + merchant)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("open"));
        WM.stubFor(get(urlPathEqualTo(base + "/items"))
                .inScenario("clover-" + merchant)
                .whenScenarioStateIs("open")
                .withQueryParam("expand", equalTo("categories,modifierGroups"))
                .withQueryParam("filter", equalTo("hidden=false"))
                .willReturn(okJson("""
                        {"elements":[
                          {"id":"IT1","name":"Lemongrass chicken banh mi","price":1295,"priceType":"FIXED","hidden":false,"available":true,
                           "categories":{"elements":[{"id":"CAT1"}]},"modifierGroups":{"elements":[{"id":"MG1"}]}},
                          {"id":"IT2","name":"Iced coffee","price":595,"priceType":"FIXED","categories":{"elements":[{"id":"CAT2"}]}},
                          {"id":"IT3","name":"Market fish","price":0,"priceType":"VARIABLE","categories":{"elements":[{"id":"CAT1"}]}}
                        ]}""")));
        WM.stubFor(get(urlPathEqualTo(base + "/modifier_groups")).willReturn(okJson("""
                        {"elements":[{"id":"MG1","name":"Spice","minRequired":1,"maxAllowed":1,
                          "modifiers":{"elements":[{"id":"MO1","name":"Mild","price":0},{"id":"MO2","name":"Hot","price":50}]}}]}""")));

        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        var consent = consent(k, "clover", menu.menuId());
        assertThat(consent.get("_url")).startsWith(WM.baseUrl() + "/clover-www/oauth/v2/authorize?");
        assertThat(consent)
                .containsEntry("client_id", "FAKECLOVERAPPID")
                .containsEntry("redirect_uri", API + "/api/v1/commerce/oauth/clover/callback");
        // a merchant id that isn't one is refused before any call
        callback(
                "clover",
                Map.of("code", "x", "state", consent.get("state"), "merchant_id", "../../evil"),
                STUDIO + "/b/" + k.merchantId() + "/kitchen/menu?pos=clover&result=failed&menu=" + menu.menuId());
        consent = consent(k, "clover", menu.menuId());
        callback(
                "clover",
                Map.of("code", "clover-code-" + merchant, "state", consent.get("state"), "merchant_id", merchant),
                STUDIO + "/b/" + k.merchantId() + "/kitchen/menu?pos=clover&result=connected&menu=" + menu.menuId());
        mvc.perform(MockMvcRequestBuilders.get(k.base() + "/pos/connections").with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.items[?(@.provider == 'clover')].accountLabel")
                        .value(hasItem("Pho Dau Bo Clover")));

        var id = previewAndApply(k, menu.menuId(), "clover", 2);
        mvc.perform(MockMvcRequestBuilders.get(k.base() + "/pos-imports/" + id).with(TestJwt.member(k.ownerId())))
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.diff.items[?(@.name == 'Market fish')].change")
                        .value(hasItem("problem")));
        assertThat(items(menu.menuId()))
                .containsExactly("Drinks/Iced coffee/595/true", "Banh mi/Lemongrass chicken banh mi/1295/true");
        assertThat(groups(k.merchantId())).containsExactly("Spice:exactly:1:true:Mild+0,Hot+50");
        // the 30-second token was refreshed; the rotated refresh token is what's sealed now
        WM.verify(postRequestedFor(urlPathEqualTo("/clover-api/oauth/v2/refresh")));
        WM.verify(2, getRequestedFor(urlPathEqualTo(base + "/items")));
    }

    // ── Toast (partner-gated) ────────────────────────────────────────────────────

    @Test
    void toast() throws Exception {
        var restaurant = java.util.UUID.randomUUID().toString();
        WM.stubFor(post("/toast/authentication/v1/authentication/login")
                .withRequestBody(containing("\"userAccessType\":\"TOAST_MACHINE_CLIENT\""))
                .withRequestBody(containing("fake-toast-partner-client"))
                .willReturn(okJson(
                        "{\"token\":{\"accessToken\":\"toast-token\",\"expiresIn\":86400,\"tokenType\":\"Bearer\"}}")));
        WM.stubFor(get("/toast/restaurants/v1/restaurants/" + restaurant)
                .withHeader("Toast-Restaurant-External-ID", equalTo(restaurant))
                .withHeader("Authorization", equalTo("Bearer toast-token"))
                .willReturn(okJson("{\"guid\":\"" + restaurant + "\",\"general\":{\"name\":\"Pho Dau Bo Toast\"}}")));
        WM.stubFor(get("/toast/restaurants/v1/restaurants/11111111-2222-4333-8444-555555555555")
                .willReturn(aResponse().withStatus(403)));
        WM.stubFor(get("/toast/menus/v2/menus")
                .withHeader("Toast-Restaurant-External-ID", equalTo(restaurant))
                .willReturn(okJson("""
                        {"restaurantGuid":"%s","lastUpdated":"2026-09-30T12:00:00.000+0000",
                         "menus":[
                           {"guid":"m-dinner","name":"Dinner","menuGroups":[
                             {"guid":"g-pho","name":"Pho","menuItems":[
                               {"guid":"i-pho","name":"Pho tai","description":"Rare beef","price":16.95,"modifierGroupReferences":[1]}],
                              "menuGroups":[{"guid":"g-pho-kids","name":"Kids","menuItems":[
                                {"guid":"i-kids","name":"Kids pho","price":9.5,"modifierGroupReferences":[]}]}]}]},
                           {"guid":"m-lunch","name":"Lunch","menuGroups":[
                             {"guid":"g-bm","name":"Banh mi","menuItems":[
                               {"guid":"i-bm","name":"Tofu banh mi","price":11.95,"modifierGroupReferences":[1]},
                               {"guid":"i-size","name":"Combo","price":null,"modifierGroupReferences":[]}]}]}],
                         "modifierGroupReferences":{"1":{"referenceId":1,"guid":"mg-extras","name":"Extras","minSelections":0,"maxSelections":2,
                           "modifierOptionReferences":[10,11]}},
                         "modifierOptionReferences":{"10":{"referenceId":10,"guid":"mo-egg","name":"Soft egg","price":1.5},
                           "11":{"referenceId":11,"guid":"mo-herbs","name":"Extra herbs","price":0}}}""".formatted(restaurant))));

        var k = fx().kitchen();
        var menu = fx().menu(k, "draft");
        postJson(
                        k.base() + "/pos/toast/connect",
                        "{\"restaurantId\":\"11111111-2222-4333-8444-555555555555\"}",
                        k.ownerId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("restaurantId"));
        postJson(
                        k.base() + "/pos/toast/connect",
                        "{\"restaurantId\":\"" + restaurant.toUpperCase(Locale.ROOT) + "\"}",
                        k.ownerId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.accountLabel").value("Pho Dau Bo Toast"));

        previewAndApply(k, menu.menuId(), "toast", 3);
        assertThat(items(menu.menuId()))
                .containsExactly(
                        "Dinner · Pho/Pho tai/1695/true",
                        "Dinner · Kids/Kids pho/950/true",
                        "Lunch · Banh mi/Tofu banh mi/1195/true");
        assertThat(groups(k.merchantId())).containsExactly("Extras:up_to:2:false:Soft egg+150,Extra herbs+0");
        WM.verify(1, postRequestedFor(urlPathEqualTo("/toast/authentication/v1/authentication/login")));
    }
}
