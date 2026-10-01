package ca.northline.discovery;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code GET /api/v1/public/home} (S-46): counts per section, category and cuisine; trusted providers. */
class HomeApiTest extends IntegrationTest {

    private static final String ALL_DAY = "[[\"00:00\",\"23:59\"]]";

    @Autowired
    JdbcClient jdbc;

    /** Every test works in its own city so the shared database's other rows never count. */
    private String city;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void city() {
        new CategorySeeder(dataSource).seed();
        city = "Testville " + Ids.next().substring(18);
    }

    private String business(String type, String name, String tier, String... categories) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, structure, tier, status, city,
                               profile)
                        values (?, ?, ?, ?, 'sole', ?, 'active', ?, '{}'::jsonb)
                        """).params(id, type, name, name + " Ltd.", tier, city).update();
        for (var c : categories) {
            jdbc.sql(
                            "insert into merchants.merchant_categories (merchant_id, category_id, status) values (?, ?, 'approved')")
                    .params(id, c)
                    .update();
        }
        return id;
    }

    private void storefront(String merchantId, String slug) {
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, published_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', now())
                        """).params(Ids.next(), merchantId, slug).update();
    }

    /** An open kitchen: all-day hours every day, a live menu with an approved dish. */
    private String kitchen(String name, String cuisines, String fulfilment, boolean live) {
        var id = business("kitchen", name, "trusted");
        jdbc.sql(
                        "update merchants.merchants set profile = jsonb_build_object('cuisines', cast(? as jsonb)) where id = ?")
                .params(cuisines, id)
                .update();
        jdbc.sql("""
                        insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, fulfilment)
                        values (?, 25, 6, cast(? as text[]))
                        """).params(id, fulfilment).update();
        for (int day = 1; day <= 7; day++) {
            jdbc.sql("insert into food.opening_hours (merchant_id, weekday, ranges) values (?, ?, cast(? as jsonb))")
                    .params(id, day, ALL_DAY)
                    .update();
        }
        var menu = Ids.next();
        jdbc.sql("insert into food.menus (id, merchant_id, name, status) values (?, ?, 'Dinner', ?)")
                .params(menu, id, live ? "live" : "draft")
                .update();
        var section = Ids.next();
        jdbc.sql("insert into food.menu_sections (id, menu_id, name, sort) values (?, ?, 'Mains', 0)")
                .params(section, menu)
                .update();
        jdbc.sql("""
                        insert into food.menu_items (id, section_id, merchant_id, name, price_cents, allergens, vetting, status)
                        values (?, ?, ?, 'Pho', 1700, '{}', 'approved', 'published')
                        """).params(Ids.next(), section, id).update();
        return id;
    }

    private void review(String merchantId, int rating) {
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating)
                        values (?, 'booking', ?, ?, 'merchant', ?, ?)
                        """)
                .params(Ids.next(), Ids.next(), Ids.next(), merchantId, rating)
                .update();
    }

    private static void notAtMidnight() {
        // "00:00"–"23:59" leaves one minute a day closed
        assumeThat(LocalTime.now(ZoneId.of("America/Edmonton"))).isBefore(LocalTime.of(23, 58));
    }

    @Test
    void guestsGetTheCitysNumbers() throws Exception {
        notAtMidnight();
        business("provider", "Bow Valley Cleaners", "master", "service.cleaning-and-property.house-cleaning");
        business("provider", "Prairie Wrench", "master", "service.automotive.mobile-mechanic");
        business(
                "both",
                "Glenmore Bakery",
                "trusted",
                "shop.food-and-grocery.bakery",
                "service.events-and-hospitality.dj");
        business("seller", "Sidewalk Citizen", "registered", "shop.food-and-grocery.bakery");
        kitchen("Pho Dau Bo", "[\"vietnamese\"]", "{courier,pickup}", true);
        kitchen("Lina's", "[\"italian\"]", "{courier,meal_kits}", true);
        kitchen("Drafty", "[\"vietnamese\"]", "{courier}", false);

        mvc.perform(get("/api/v1/public/home").param("city", city.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.providers").value(3))
                .andExpect(jsonPath("$.shops").value(2))
                .andExpect(jsonPath("$.kitchensOpen").value(2))
                .andExpect(
                        jsonPath("$.categories['shop.food-and-grocery.bakery']").value(2))
                .andExpect(jsonPath("$.categories['service.automotive.mobile-mechanic']")
                        .value(1))
                .andExpect(jsonPath("$.cuisines.vietnamese").value(1))
                .andExpect(jsonPath("$.cuisines.italian").value(1))
                .andExpect(jsonPath("$.cuisines.meal_kits").value(1));
    }

    @Test
    void pausedAndAutoPausedKitchensDontCountAsOpen() throws Exception {
        notAtMidnight();
        var paused = kitchen("Paused", "[\"pizza\"]", "{courier}", true);
        jdbc.sql("update food.kitchen_settings set paused_until = ? where merchant_id = ?")
                .params(JdbcTimes.ts(Instant.now().plus(Duration.ofMinutes(20))), paused)
                .update();
        var late = kitchen("Late", "[\"pizza\"]", "{courier}", true);
        jdbc.sql("update food.kitchen_settings set auto_pause_late = 3 where merchant_id = ?")
                .param(late)
                .update();
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                            insert into food.kitchen_tickets (order_id, merchant_id, stage, prep_min, accepted_at, ready_by)
                            values (?, ?, 'cooking', 25, now() - interval '40 minutes', now() - interval '10 minutes')
                            """).params(Ids.next(), late).update();
        }
        kitchen("Open", "[\"pizza\"]", "{courier}", true);

        mvc.perform(get("/api/v1/public/home").param("city", city))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchensOpen").value(1))
                .andExpect(jsonPath("$.cuisines.pizza").value(1));
    }

    @Test
    void trustedProvidersAreTheBestRatedWithTheirPageAndCategory() throws Exception {
        var a = business("provider", "Alpha Plumbing", "registered", "service.home-trades.plumber");
        var b = business("provider", "Bravo Mechanics", "master", "service.automotive.mobile-mechanic");
        var c = business("both", "Charlie Movers", "trusted", "service.cleaning-and-property.movers");
        var d = business("provider", "Delta Unrated", "master");
        storefront(b, "bravo-" + b.toLowerCase());
        review(a, 4);
        review(b, 5);
        review(b, 5);
        review(c, 5);
        mvc.perform(get("/api/v1/public/home").param("city", city).header("Accept-Language", "en-CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trusted", hasSize(3)))
                .andExpect(jsonPath("$.trusted[0].merchantId").value(b))
                .andExpect(jsonPath("$.trusted[0].slug").value("bravo-" + b.toLowerCase()))
                .andExpect(jsonPath("$.trusted[0].rating").value(5.0))
                .andExpect(jsonPath("$.trusted[0].reviews").value(2))
                .andExpect(jsonPath("$.trusted[0].tier").value("master"))
                .andExpect(jsonPath("$.trusted[1].merchantId").value(c))
                .andExpect(jsonPath("$.trusted[1].slug").value(nullValue()))
                .andExpect(jsonPath("$.trusted[0].category.id").value("service.automotive.mobile-mechanic"))
                .andExpect(jsonPath("$.trusted[0].category.name").value("Mobile mechanic"))
                .andExpect(jsonPath("$.trusted[2].merchantId").value(a))
                .andExpect(jsonPath("$.trusted[*].merchantId", not(hasItem(d))));
    }

    @Test
    void signedInCustomersMayCallItToo() throws Exception {
        mvc.perform(get("/api/v1/public/home").param("city", city).with(TestJwt.customer(data.user("Amara"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value(city))
                .andExpect(jsonPath("$.providers").value(0))
                .andExpect(jsonPath("$.trusted", hasSize(0)));
    }

    @Test
    void aCityIsRequired() throws Exception {
        mvc.perform(get("/api/v1/public/home"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("city"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a city."));
        mvc.perform(get("/api/v1/public/home").param("city", "x".repeat(61)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("At most 60 characters."));
    }
}
