package ca.northline.catalogue;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static ca.northline.support.ShopFixtures.CLOTHING;
import static ca.northline.support.ShopFixtures.PRODUCE;
import static ca.northline.support.ShopFixtures.TOBACCO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.PlaceParams;
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

    @Autowired
    Regions regions;

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
        // real photos (2026-10-19): the card's category stands in for a missing image; a shop without images has none
        assertThat(card.path("categoryId").asString()).isEqualTo(BAKERY);
        assertThat(glenmore.path("imageUrl").isMissingNode()
                        || glenmore.path("imageUrl").isNull())
                .isTrue();
    }

    @Test
    void aShopsCardShowsItsBestSellersApprovedImage() throws Exception {
        var tag = Ids.next().substring(18);
        var bakery = shopFixtures.shop(MARKET, "Photo Bakery " + tag, "master");
        var loaf =
                shopFixtures.listing(bakery, BAKERY, "Photo loaf " + tag, 750, 12, "approved", "live", "same_day", 40);
        var media = Ids.next();
        jdbc.sql("""
                        insert into catalogue.media (id, url, exif_ok, kind, merchant_id, content_type, width, height,
                          byte_size, on_white)
                        values (:id, :key, true, 'gallery', :m, 'image/jpeg', 1200, 1200, 1000, true)
                        """)
                .param("id", media)
                .param("key", "merchants/" + bakery + "/" + media + ".jpg")
                .param("m", bakery)
                .update();
        jdbc.sql("update catalogue.offers set own_images = array[:media] where id = :id")
                .param("media", media)
                .param("id", loaf.offerId())
                .update();

        var landing = json(mvc.perform(get("/api/v1/public/shop").param("market", "shopville"))
                .andExpect(status().isOk()));
        assertThat(find(landing.path("shops"), "merchantId", bakery)
                        .path("imageUrl")
                        .asString())
                .isEqualTo("/api/v1/public/catalogue/media/" + media);
        assertThat(find(landing.path("popular"), "productId", loaf.productId())
                        .path("imageUrl")
                        .asString())
                .isEqualTo("/api/v1/public/catalogue/media/" + media);
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
    void aMalformedMarketOrPointIsRefused() throws Exception {
        mvc.perform(get("/api/v1/public/shop").param("market", "x".repeat(61)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("market"))
                .andExpect(jsonPath("$.errors[0].message").value("At most 60 characters."));
        mvc.perform(get("/api/v1/public/shop/departments/bakery").param("market", "<script>"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("market"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter a city name."));
        mvc.perform(get("/api/v1/public/shop").param("lat", "53.5"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Send both lat and lng, or neither."));
        mvc.perform(get("/api/v1/public/shop").param("lat", "95").param("lng", "-113"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Latitude must be between -90 and 90."));
    }

    /** S-190: a location is never required — no market shows every market Northline delivers in, by popularity. */
    @Test
    void withoutAMarketEveryMarketIsShown_andNothingClaimsARun() throws Exception {
        var tag = Ids.next().substring(18);
        var here = shopFixtures.shop(MARKET, "Here Butcher " + tag, "master");
        var steak = shopFixtures.listing(here, BUTCHER, "Here steak " + tag, 1900, 5);
        var there = shopFixtures.shop("Productville", "There Butcher " + tag, "trusted");
        var sausage = shopFixtures.listing(there, BUTCHER, "There sausage " + tag, 900, 5);

        // no market, and a blank one (a page that never learnt the place): the same answer
        for (var request :
                List.of(get("/api/v1/public/shop"), get("/api/v1/public/shop").param("market", " "))) {
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=60, public"))
                    .andExpect(jsonPath("$.market").doesNotExist())
                    .andExpect(jsonPath("$.served").value(true))
                    .andExpect(jsonPath("$.run").doesNotExist())
                    .andExpect(jsonPath("$.nearby").isEmpty());
        }
        var page =
                json(mvc.perform(get("/api/v1/public/shop/departments/butcher")).andExpect(status().isOk()));
        assertThat(page.path("market").isNull()).isTrue();
        var products = page.path("products");
        var mine = find(products, "productId", steak.productId());
        assertThat(mine.path("shipsFrom").asString()).isEqualTo(MARKET);
        assertThat(mine.path("deliverable").isNull()).isTrue();
        assertThat(mine.path("run").isNull()).isTrue();
        assertThat(find(products, "productId", sausage.productId())
                        .path("shipsFrom")
                        .asString())
                .isEqualTo("Productville");
        assertThat(texts(page.path("shops"), "name")).contains("Here Butcher " + tag, "There Butcher " + tag);
        assertThat(find(page.path("shops"), "merchantId", there).path("city").asString())
                .isEqualTo("Productville");

        // a product page with no market: every market's offers, none on a run, no courier promise
        var product = json(mvc.perform(get("/api/v1/public/shop/products/{id}", sausage.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").doesNotExist())
                .andExpect(jsonPath("$.direct").doesNotExist())
                .andExpect(jsonPath("$.elsewhere").isEmpty()));
        var offer = find(product.path("offers"), "merchantId", there);
        assertThat(offer.path("shipsFrom").asString()).isEqualTo("Productville");
        assertThat(offer.path("runs").isEmpty()).isTrue();
    }

    /**
     * S-190: with a point, every market's products come nearest market first; with a market, the other markets' are
     * listed apart, nearest first, labelled not deliverable — never mixed into the market's own lists.
     */
    @Test
    void nearerMarketsComeFirst_andOtherMarketsAreLabelledNotHidden() throws Exception {
        var located = regions.liveMarkets().stream()
                .filter(m -> m.lat() != null && m.lng() != null)
                .toList();
        assumeThat(located.size())
                .as("two live markets with a centre in the region data")
                .isGreaterThanOrEqualTo(2);
        var near = located.getFirst();
        var nearLat = java.util.Objects.requireNonNull(near.lat());
        var nearLng = java.util.Objects.requireNonNull(near.lng());
        // the other live market nearest to it
        var far = located.stream()
                .skip(1)
                .min(java.util.Comparator.comparingDouble(m -> PlaceParams.km(
                        nearLat,
                        nearLng,
                        java.util.Objects.requireNonNull(m.lat()),
                        java.util.Objects.requireNonNull(m.lng()))))
                .orElseThrow();
        var tag = Ids.next().substring(18);
        var nearShop = shopFixtures.shop(near.city(), "Near Deli " + tag, "registered");
        var nearHam = shopFixtures.listing(nearShop, BUTCHER, "Near ham " + tag, 1200, 5);
        var farShop = shopFixtures.shop(far.city(), "Far Deli " + tag, "master");
        var farHam = shopFixtures.listing(farShop, BUTCHER, "Far ham " + tag, 1100, 5);

        // a point at the near market's centre: its products before the far one's
        var everywhere = json(mvc.perform(get("/api/v1/public/shop/departments/butcher")
                        .param("lat", String.valueOf(nearLat))
                        .param("lng", String.valueOf(nearLng)))
                .andExpect(status().isOk()));
        var ids = texts(everywhere.path("products"), "productId");
        assertThat(ids).contains(nearHam.productId(), farHam.productId());
        assertThat(ids.indexOf(nearHam.productId())).isLessThan(ids.indexOf(farHam.productId()));
        var shipsFrom = texts(everywhere.path("products"), "shipsFrom");
        assertThat(shipsFrom.indexOf(far.city())).isGreaterThan(shipsFrom.lastIndexOf(near.city()));

        // the near market: its own products deliverable, the far market's apart and labelled
        var market =
                json(mvc.perform(get("/api/v1/public/shop/departments/butcher").param("market", near.city()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.market").value(near.city())));
        assertThat(texts(market.path("products"), "productId"))
                .contains(nearHam.productId())
                .doesNotContain(farHam.productId());
        assertThat(find(market.path("products"), "productId", nearHam.productId())
                        .path("deliverable")
                        .asBoolean())
                .isTrue();
        var nearby = market.path("nearby");
        assertThat(nearby.isEmpty()).isFalse();
        assertThat(nearby.get(0).path("shipsFrom").asString()).isEqualTo(far.city());
        nearby.forEach(card -> {
            assertThat(card.path("deliverable").asBoolean(true)).isFalse();
            assertThat(card.path("shipsFrom").asString()).isNotEqualToIgnoringCase(near.city());
            assertThat(card.path("run").isNull()).isTrue();
        });

        // the far product's page from the near market: not offered there, its shop listed under elsewhere
        var product = json(mvc.perform(get("/api/v1/public/shop/products/{id}", farHam.productId())
                        .param("market", near.city()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers").isEmpty()));
        var away = find(product.path("elsewhere"), "merchantId", farShop);
        assertThat(away.path("deliverable").asBoolean(true)).isFalse();
        assertThat(away.path("shipsFrom").asString()).isEqualTo(far.city());
        assertThat(away.path("runs").isEmpty()).isTrue();
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
