package ca.northline.catalogue;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static ca.northline.support.ShopFixtures.CLOTHING;
import static ca.northline.support.ShopFixtures.PRODUCE;
import static ca.northline.support.ShopFixtures.TOBACCO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-49: the consumer Shop landing and department pages — public (no token), only approved live offers of active
 * sellers of the market, in categories that aren't banned; the next pooled run with its cut-off. This class browses
 * the test market "Shopville" (application-test.yml).
 */
class PublicShopApiTest extends IntegrationTest {

    static final String MARKET = "Shopville";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void freshMarket() {
        shopFixtures.categories();
        shopFixtures.closeMarket(MARKET); // the shared database keeps earlier runs' sellers
    }

    @Test
    void landingShowsOnlyLiveOffersOfActiveSellersInTheMarket() throws Exception {
        var tag = Ids.next().substring(18);
        var bakery = shopFixtures.shop(MARKET, "Glenmore Bakery " + tag, "master");
        var sourdough = shopFixtures.listing(
                bakery, BAKERY, "Country sourdough " + tag, 750, 12, "approved", "live", "same_day", 40);
        shopFixtures.listing(bakery, BAKERY, "Rye " + tag, 800, 5);
        var butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher " + tag, "trusted");
        shopFixtures.listing(butcher, BUTCHER, "Ribeye " + tag, 1850, 4, "approved", "live", "next_day", 30);

        // none of these may appear
        shopFixtures.listing(bakery, BAKERY, "Draft loaf " + tag, 500, 9, "draft", "hidden", "same_day", 99);
        shopFixtures.listing(bakery, BAKERY, "Pending loaf " + tag, 500, 9, "pending", "hidden", "same_day", 99);
        shopFixtures.listing(bakery, BAKERY, "Hidden loaf " + tag, 500, 9, "approved", "hidden", "same_day", 99);
        var paused = shopFixtures.merchant(MARKET, "Paused Pantry " + tag, "trusted", "seller", "paused");
        shopFixtures.listing(paused, PRODUCE, "Paused kale " + tag, 425, 9, "approved", "live", "same_day", 99);
        var provider = shopFixtures.merchant(MARKET, "Provider " + tag, "master", "provider", "active");
        shopFixtures.listing(provider, PRODUCE, "Provider kale " + tag, 425, 9, "approved", "live", "same_day", 99);
        var elsewhere = shopFixtures.shop("Edmonton", "Edmonton Greens " + tag, "master");
        shopFixtures.listing(elsewhere, PRODUCE, "Edmonton kale " + tag, 425, 9, "approved", "live", "same_day", 99);
        var vape = shopFixtures.shop(MARKET, "Vape " + tag, "registered");
        shopFixtures.listing(vape, TOBACCO, "Vape pen " + tag, 2500, 9, "approved", "live", "same_day", 99);

        var landing = json(mvc.perform(get("/api/v1/public/shop").param("market", "shopville"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.market").value(MARKET))
                .andExpect(jsonPath("$.served").value(true))
                .andExpect(jsonPath("$.run.orderBy").exists()));

        var names = texts(landing.path("popular"), "name");
        assertThat(names).contains("Country sourdough " + tag, "Ribeye " + tag, "Rye " + tag);
        assertThat(names).noneMatch(n -> n.contains("loaf " + tag) || n.contains("kale " + tag) || n.contains("Vape"));
        var shops = texts(landing.path("shops"), "name");
        assertThat(shops).contains("Glenmore Bakery " + tag, "Bridgeland Butcher " + tag);
        assertThat(shops)
                .doesNotContain("Paused Pantry " + tag, "Provider " + tag, "Edmonton Greens " + tag, "Vape " + tag);
        assertThat(texts(landing.path("departments"), "slug"))
                .contains("bakery", "butcher")
                .doesNotContain("tobacco-and-vape");

        var card = find(landing.path("popular"), "productId", sourdough.productId());
        assertThat(card.path("priceCents").asLong()).isEqualTo(750);
        assertThat(card.path("shopName").asString()).isEqualTo("Glenmore Bakery " + tag);
        assertThat(card.path("unit").asString()).isEqualTo("900 g");
        assertThat(card.path("run").path("windowId").asString())
                .isEqualTo(landing.path("run").path("windowId").asString());
        var glenmore = find(landing.path("shops"), "merchantId", bakery);
        assertThat(glenmore.path("tier").asString()).isEqualTo("master");
        assertThat(glenmore.path("departmentSlug").asString()).isEqualTo("bakery");
        assertThat(glenmore.path("products").asInt()).isEqualTo(2);
    }

    @Test
    void theNextRunIsOpenAndCountsHouseholdsOnIt() throws Exception {
        var landing = json(
                mvc.perform(get("/api/v1/public/shop").param("market", MARKET)).andExpect(status().isOk()));
        var run = landing.path("run");
        assertThat(Instant.parse(run.path("orderBy").asString())).isAfter(Instant.now());
        assertThat(Instant.parse(run.path("startsAt").asString()))
                .isAfter(Instant.parse(run.path("orderBy").asString()));
        assertThat(run.path("label").asString()).startsWith("R-");
        assertThat(run.path("day").asString()).isIn("today", "tomorrow");
        assertThat(run.path("feeCents").asLong()).isIn(299L, 199L);

        var windowId = run.path("windowId").asString();
        var before = run.path("households").asInt();
        for (var customer : List.of(Ids.next(), Ids.next())) {
            jdbc.sql("""
                            insert into orders.orders (id, customer_id, type, state, window_id, subtotal_cents)
                            values (?, ?, 'goods', 'placed', ?, 1000)
                            """).params(Ids.next(), customer, windowId).update();
        }
        var again = json(
                mvc.perform(get("/api/v1/public/shop").param("market", MARKET)).andExpect(status().isOk()));
        assertThat(again.path("run").path("windowId").asString()).isEqualTo(windowId);
        assertThat(again.path("run").path("households").asInt()).isEqualTo(before + 2);
    }

    @Test
    void shopsAreOnTheRunTheirHandlingTimeAndStockAllow() throws Exception {
        var tag = Ids.next().substring(18);
        var slow = shopFixtures.shop(MARKET, "Slow Tailor " + tag, "registered");
        shopFixtures.listing(slow, CLOTHING, "Wool coat " + tag, 18000, 2, "approved", "live", "two_days", 0);
        var empty = shopFixtures.shop(MARKET, "Empty Shelf " + tag, "registered");
        shopFixtures.listing(empty, CLOTHING, "Sold out scarf " + tag, 3000, 0);
        var pickupOnly = shopFixtures.shop(MARKET, "Pickup Only " + tag, "registered");
        var hat = shopFixtures.listing(pickupOnly, CLOTHING, "Hat " + tag, 3000, 3);
        shopFixtures.fulfilment(hat.offerId(), "pickup");

        var page =
                json(mvc.perform(get("/api/v1/public/shop/departments/clothing").param("market", MARKET))
                        .andExpect(status().isOk()));
        assertThat(find(page.path("shops"), "merchantId", slow)
                        .path("run")
                        .path("day")
                        .asString())
                .isEqualTo("later");
        assertThat(find(page.path("shops"), "merchantId", empty).path("run").isNull())
                .isTrue();
        assertThat(find(page.path("shops"), "merchantId", pickupOnly)
                        .path("run")
                        .isNull())
                .isTrue();
    }

    @Test
    void departmentPageListsItsShopsProductsAndSiblingDepartments() throws Exception {
        var tag = Ids.next().substring(18);
        var bakery = shopFixtures.shop(MARKET, "Sidewalk Citizen " + tag, "trusted");
        var popular =
                shopFixtures.listing(bakery, BAKERY, "Croissants " + tag, 1200, 8, "approved", "live", "same_day", 500);
        var quiet = shopFixtures.listing(bakery, BAKERY, "Baguette " + tag, 450, 8, "approved", "live", "same_day", 0);
        var other = shopFixtures.shop(MARKET, "Glenmore " + tag, "master");
        // a second seller of the same product: the page shows one card, at the cheaper price, with 2 sellers
        shopFixtures.offer(other, popular.productId(), "Croissants", 1100, 3, "approved", "live", "same_day", 10);
        var produce = shopFixtures.shop(MARKET, "Sunnyside " + tag, "master");
        shopFixtures.listing(produce, PRODUCE, "Carrots " + tag, 300, 8);

        var page = json(mvc.perform(
                        get("/api/v1/public/shop/departments/{slug}", "bakery").param("market", MARKET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("bakery"))
                .andExpect(jsonPath("$.name").value("Bakery"))
                .andExpect(jsonPath("$.groupName").value("Food & grocery")));

        var products = page.path("products");
        var croissants = find(products, "productId", popular.productId());
        assertThat(croissants.path("priceCents").asLong()).isEqualTo(1100);
        assertThat(croissants.path("sellers").asInt()).isEqualTo(2);
        assertThat(croissants.path("shopName").asString()).isEqualTo("Glenmore " + tag);
        var order = texts(products, "productId");
        assertThat(order.indexOf(popular.productId())).isLessThan(order.indexOf(quiet.productId()));
        assertThat(texts(products, "name")).doesNotContain("Carrots " + tag);
        assertThat(texts(page.path("shops"), "merchantId"))
                .contains(bakery, other)
                .doesNotContain(produce);
        assertThat(texts(page.path("siblings"), "slug"))
                .contains("bakery", "produce")
                .doesNotContain("clothing");
        assertThat(page.path("productCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("onRunCount").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void namesAreInFrenchWhenAsked() throws Exception {
        mvc.perform(get("/api/v1/public/shop/departments/bakery")
                        .param("market", MARKET)
                        .param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Boulangerie"))
                .andExpect(jsonPath("$.groupName").value("Alimentation et épicerie"));
        mvc.perform(get("/api/v1/public/shop/departments/bakery")
                        .param("market", MARKET)
                        .header("Accept-Language", "fr-CA"))
                .andExpect(jsonPath("$.name").value("Boulangerie"));
    }

    @Test
    void aCityWithoutDeliveryIsEmptyAndUnknownDepartmentsAre404() throws Exception {
        var tag = Ids.next().substring(18);
        var shop = shopFixtures.shop("Red Deer", "Red Deer Bakery " + tag, "master");
        shopFixtures.listing(shop, BAKERY, "Loaf " + tag, 500, 3);
        mvc.perform(get("/api/v1/public/shop").param("market", "Red Deer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("Red Deer"))
                .andExpect(jsonPath("$.served").value(false))
                .andExpect(jsonPath("$.run").isEmpty())
                .andExpect(jsonPath("$.popular").isEmpty())
                .andExpect(jsonPath("$.shops").isEmpty());
        mvc.perform(get("/api/v1/public/shop/departments/bakery").param("market", "Red Deer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.served").value(false))
                .andExpect(jsonPath("$.products").isEmpty());
        mvc.perform(get("/api/v1/public/shop/departments/no-such-aisle").param("market", MARKET))
                .andExpect(status().isNotFound());
        // a group isn't a department; a banned leaf isn't either
        mvc.perform(get("/api/v1/public/shop/departments/food-and-grocery").param("market", MARKET))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/shop/departments/tobacco-and-vape").param("market", MARKET))
                .andExpect(status().isNotFound());
    }

    @Test
    void marketIsValidated() throws Exception {
        mvc.perform(get("/api/v1/public/shop").param("market", " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("market"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a city."));
        mvc.perform(get("/api/v1/public/shop").param("market", "x".repeat(61)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a city."));
    }

    static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static List<String> texts(JsonNode array, String field) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }

    static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> value.equals(n.path(field).asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(field + "=" + value + " not in " + array));
    }
}
