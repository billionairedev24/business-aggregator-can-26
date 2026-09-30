package ca.northline.catalogue;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.TOBACCO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-50: the product page — the catalogue record, every shop of the market selling it (best first), variants and stock,
 * and the pooled runs with their cut-off computed server-side in America/Edmonton. Market "Productville".
 */
class ProductPageApiTest extends IntegrationTest {

    static final String MARKET = "Productville";
    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void freshMarket() {
        shopFixtures.categories();
        shopFixtures.closeMarket(MARKET);
    }

    @Test
    void showsTheRecordAndEveryShopSellingItBestFirst() throws Exception {
        var tag = Ids.next().substring(18);
        var glenmore = shopFixtures.shop(MARKET, "Glenmore " + tag, "master");
        var sourdough = shopFixtures.listing(glenmore, BAKERY, "Country sourdough " + tag, 750, 0);
        var whole = shopFixtures.variant(sourdough.offerId(), "Whole", 750, 4, 0);
        shopFixtures.variant(sourdough.offerId(), "Sliced", 800, 0, 1);
        shopFixtures.listing(glenmore, BAKERY, "Rye " + tag, 800, 5);
        var cheaper = shopFixtures.shop(MARKET, "Sidewalk " + tag, "trusted");
        shopFixtures.offer(cheaper, sourdough.productId(), "Sourdough", 700, 3, "approved", "live", "two_days", 0);
        var soldOut = shopFixtures.shop(MARKET, "Sold Out " + tag, "master");
        shopFixtures.offer(soldOut, sourdough.productId(), "Sourdough", 600, 0, "approved", "live", "same_day", 0);
        var pending = shopFixtures.shop(MARKET, "Pending " + tag, "master");
        shopFixtures.offer(pending, sourdough.productId(), "Sourdough", 500, 9, "pending", "hidden", "same_day", 0);
        var elsewhere = shopFixtures.shop("Edmonton", "Elsewhere " + tag, "master");
        shopFixtures.offer(elsewhere, sourdough.productId(), "Sourdough", 400, 9, "approved", "live", "same_day", 0);

        var page = json(mvc.perform(get("/api/v1/public/shop/products/{id}", sourdough.productId())
                        .param("market", MARKET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Country sourdough " + tag))
                .andExpect(jsonPath("$.unit").value("900 g"))
                .andExpect(jsonPath("$.departmentSlug").value("bakery"))
                .andExpect(jsonPath("$.bullets[0]").value("Hand made"))
                .andExpect(jsonPath("$.direct.etaMinutes").value(45))
                .andExpect(jsonPath("$.direct.feeCents").value(999)));

        var offers = page.path("offers");
        // in stock and on the earliest run first; the sold-out shop last; pending and other markets absent
        assertThat(texts(offers, "shopName")).containsExactly("Glenmore " + tag, "Sidewalk " + tag, "Sold Out " + tag);
        var first = offers.get(0);
        assertThat(first.path("tier").asString()).isEqualTo("master");
        assertThat(first.path("stock").asInt()).isEqualTo(4);
        assertThat(first.path("priceCents").asLong()).isEqualTo(750); // cheapest variant in stock
        assertThat(first.path("variants").get(0).path("variantId").asString()).isEqualTo(whole);
        assertThat(texts(first.path("variants"), "value")).containsExactly("Whole", "Sliced");
        assertThat(first.path("variants").get(1).path("stock").asInt()).isZero();
        assertThat(texts(first.path("more"), "name")).containsExactly("Rye " + tag);
        assertThat(first.path("runs").size()).isEqualTo(2);
        assertThat(offers.get(2).path("stock").asInt()).isZero();
        assertThat(offers.get(2).path("runs").isEmpty()).isTrue();
    }

    @Test
    void cutOffIsComputedInEdmontonFromTheRunAndHandlingTime() throws Exception {
        var tag = Ids.next().substring(18);
        var shop = shopFixtures.shop(MARKET, "Cutoff " + tag, "master");
        var sameDay = shopFixtures.listing(shop, BAKERY, "Bread " + tag, 500, 5);
        var nextDay = shopFixtures.listing(shop, BAKERY, "Cake " + tag, 3000, 5, "approved", "live", "next_day", 0);

        var run = json(mvc.perform(get("/api/v1/public/shop/products/{id}", sameDay.productId())
                        .param("market", MARKET)))
                .path("offers")
                .get(0)
                .path("runs")
                .get(0);
        var orderBy = Instant.parse(run.path("orderBy").asString());
        var packBy = Instant.parse(run.path("packBy").asString());
        var startsAt = Instant.parse(run.path("startsAt").asString());
        assertThat(orderBy).isAfter(Instant.now());
        assertThat(Duration.between(orderBy, packBy)).isEqualTo(Duration.ofMinutes(25));
        // the configured runs: 6 pm (pack by 5:45 pm) or 8 am (pack by 7:30 am), Edmonton time
        var local = startsAt.atZone(ZONE).toLocalTime().toString();
        assertThat(local).isIn("18:00", "08:00");
        assertThat(packBy.atZone(ZONE).toLocalTime().toString()).isEqualTo(local.equals("18:00") ? "17:45" : "07:30");

        var later = json(mvc.perform(get("/api/v1/public/shop/products/{id}", nextDay.productId())
                        .param("market", MARKET)))
                .path("offers")
                .get(0)
                .path("runs")
                .get(0);
        var day = Instant.parse(later.path("startsAt").asString()).atZone(ZONE).toLocalDate();
        assertThat(day).isAfterOrEqualTo(LocalDate.now(ZONE).plusDays(1));
        assertThat(later.path("day").asString()).isIn("tomorrow", "later");
    }

    @Test
    void frenchTitlesAndAMarketWithoutSellers() throws Exception {
        var tag = Ids.next().substring(18);
        var shop = shopFixtures.shop("Edmonton", "Only Edmonton " + tag, "master");
        var loaf = shopFixtures.listing(shop, BAKERY, "Loaf " + tag, 500, 5);
        jdbc.sql(
                        "update catalogue.catalog_products set title_i18n = title_i18n || jsonb_build_object('fr', ?::text) where id = ?")
                .params("Miche " + tag, loaf.productId())
                .update();
        mvc.perform(get("/api/v1/public/shop/products/{id}", loaf.productId())
                        .param("market", MARKET)
                        .param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Miche " + tag))
                .andExpect(jsonPath("$.departmentName").value("Boulangerie"))
                .andExpect(jsonPath("$.served").value(true))
                .andExpect(jsonPath("$.offers").isEmpty());
    }

    @Test
    void unpublishedAndUnknownProductsAre404() throws Exception {
        var tag = Ids.next().substring(18);
        var shop = shopFixtures.shop(MARKET, "Drafts " + tag, "master");
        var draft = shopFixtures.listing(shop, BAKERY, "Draft " + tag, 500, 5, "draft", "hidden", "same_day", 0);
        var banned = shopFixtures.listing(shop, TOBACCO, "Vape " + tag, 500, 5);
        mvc.perform(get("/api/v1/public/shop/products/{id}", draft.productId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/shop/products/{id}", banned.productId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/shop/products/{id}", Ids.next())).andExpect(status().isNotFound());
    }

    static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static List<String> texts(JsonNode array, String field) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.path(field).asString()));
        return out;
    }
}
