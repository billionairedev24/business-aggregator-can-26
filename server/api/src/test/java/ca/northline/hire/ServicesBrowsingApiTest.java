package ca.northline.hire;

import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import ca.northline.tools.CategorySeeder;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S-53: services landing, service category and provider list — public, SSR-friendly reads. */
class ServicesBrowsingApiTest extends IntegrationTest {

    static final String MECHANIC = "service.automotive.mobile-mechanic";
    static final String BARBER = "service.personal-care-and-wellness.barber-and-hair";
    /** Inside the Beltline zone (17 Ave SW). */
    static final String BELTLINE = "51.0385";

    static final String BELTLINE_LNG = "-114.0720";

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    HireFixtures fx;

    @BeforeEach
    void seed() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
    }

    @Test
    void landingListsTheTaxonomyInSeedOrderWithLiveCounts() throws Exception {
        var p = fx.provider("Landing Wrench", "master", List.of("Beltline"));
        fx.service(p.merchantId(), MECHANIC, "Brake inspection", "fixed", 8900L, 60);

        mvc.perform(get("/api/v1/public/services"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.groups[0].key").value("automotive"))
                .andExpect(jsonPath("$.groups[0].note").value("AMVIC licence checked"))
                .andExpect(jsonPath("$.groups[0].items[0].slug").value("mobile-mechanic"))
                .andExpect(jsonPath("$.groups[0].items[0].kind").value("visit"))
                .andExpect(jsonPath(
                        "$.groups[*].key", containsInRelativeOrder("automotive", "home-trades", "professional")))
                .andExpect(jsonPath("$.liveCategories").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.provinces[0]").value("AB"));
    }

    @Test
    void categoryHasItsBookingTypeLicenceAndTypicalPrices() throws Exception {
        var a = fx.provider("Price A", "trusted", List.of("Beltline"));
        var b = fx.provider("Price B", "trusted", List.of("Beltline"));
        fx.service(a.merchantId(), "service.automotive.windshield-repair", "Chip repair", "fixed", 9900L, 45);
        fx.service(b.merchantId(), "service.automotive.windshield-repair", "chip repair ", "fixed", 7900L, 45);

        mvc.perform(get("/api/v1/public/services/windshield-repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("service.automotive.windshield-repair"))
                .andExpect(jsonPath("$.names.en").value("Windshield repair"))
                .andExpect(jsonPath("$.kind").value("visit"))
                .andExpect(jsonPath("$.vehicle").value(true))
                .andExpect(jsonPath("$.quoteable").value(true))
                .andExpect(jsonPath("$.group.key").value("automotive"))
                .andExpect(jsonPath("$.jobs.length()").value(1))
                .andExpect(jsonPath("$.jobs[0].priceCents").value(7900))
                .andExpect(jsonPath("$.jobs[0].included").value("Written report with photos."))
                .andExpect(jsonPath("$.providers").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        mvc.perform(get("/api/v1/public/services/mobile-mechanic"))
                .andExpect(jsonPath("$.regulatedRegistry").value("AMVIC"));
        mvc.perform(get("/api/v1/public/services/real-estate-agent"))
                .andExpect(jsonPath("$.kind").value("consult"))
                .andExpect(jsonPath("$.quoteable").value(false));
    }

    @Test
    void unknownCategoryIs404() throws Exception {
        mvc.perform(get("/api/v1/public/services/unicorn-grooming")).andExpect(status().isNotFound());
    }

    @Nested
    class Providers {

        @Test
        void keepsOnlyProvidersWhoseServiceAreaCoversTheCustomer() throws Exception {
            var beltline = fx.provider("Beltline Wrench", "trusted", List.of("Beltline", "Downtown"));
            var okotoks = fx.provider("Okotoks Wrench", "trusted", List.of("Okotoks"));
            fx.service(beltline.merchantId(), MECHANIC, "Oil & filter", "fixed", 7900L, 45);
            fx.service(okotoks.merchantId(), MECHANIC, "Oil & filter", "fixed", 6900L, 45);

            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers")
                            .param("lat", BELTLINE)
                            .param("lng", BELTLINE_LNG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.area").value("Beltline"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.kind").value("visit"))
                    .andExpect(jsonPath("$.items[*].merchantId", hasItem(beltline.merchantId())))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(okotoks.merchantId()))));
            // Okotoks' town centre
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers")
                            .param("lat", "50.7256")
                            .param("lng", "-113.9749"))
                    .andExpect(jsonPath("$.area").value("Okotoks"))
                    .andExpect(jsonPath("$.items[*].merchantId", hasItem(okotoks.merchantId())))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(beltline.merchantId()))));
            // city only (no device location): zones in that city; nothing at all = Calgary
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers").param("city", "Okotoks"))
                    .andExpect(jsonPath("$.items[*].merchantId", hasItem(okotoks.merchantId())))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(beltline.merchantId()))));
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.area").doesNotExist())
                    .andExpect(jsonPath("$.items[*].merchantId", hasItem(beltline.merchantId())));
        }

        @Test
        void cardsCarryTrustPriceAndNextSlot_mostTrustedFirst() throws Exception {
            var master = fx.provider("Trust Master", "master", List.of("Kensington"));
            var trusted = fx.provider("Trust Trusted", "trusted", List.of("Kensington"));
            var trustedLate = fx.provider("Trust Late", "trusted", List.of("Kensington"));
            for (var p : List.of(master, trusted, trustedLate)) {
                fx.service(p.merchantId(), "service.automotive.detailing", "Interior detail", "fixed", 15000L, 90);
            }
            fx.service(master.merchantId(), "service.automotive.detailing", "Quick wash", "fixed", 4500L, 30);
            fx.quality(master.merchantId(), 91, 0.5, 50);
            fx.quality(trusted.merchantId(), 99, 0.3, 70);
            fx.quality(trustedLate.merchantId(), 90, 0.3, 70);

            mvc.perform(get("/api/v1/public/services/detailing/providers").param("city", "Calgary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.items[*].merchantId",
                            containsInRelativeOrder(
                                    master.merchantId(), trusted.merchantId(), trustedLate.merchantId())))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].fromCents".formatted(master.merchantId()))
                            .value(4500))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].onTimePct".formatted(trusted.merchantId()))
                            .value(99.0))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].slug".formatted(master.merchantId()))
                            .value(master.slug()))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].zones[0]".formatted(master.merchantId()))
                            .value("Kensington"))
                    .andExpect(jsonPath("$.items[*].nextAvailable", everyItem(notNullValue())))
                    .andExpect(jsonPath("$.items[?(@.merchantId == '%s')].instantBook".formatted(master.merchantId()))
                            .value(true));
        }

        @Test
        void unpublishedOrPausedBusinessesAreNotListed() throws Exception {
            var hidden = fx.provider("Hidden Wrench", "master", List.of("Inglewood"));
            var paused = fx.provider("Paused Wrench", "master", List.of("Inglewood"));
            fx.service(hidden.merchantId(), MECHANIC, "Diagnostic scan", "fixed", 12000L, 60);
            fx.service(paused.merchantId(), MECHANIC, "Diagnostic scan", "fixed", 12000L, 60);
            fx.unpublish(hidden.merchantId());
            jdbc.sql("update merchants.merchants set status = 'paused' where id = ?")
                    .params(paused.merchantId())
                    .update();

            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers").param("city", "Calgary"))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(hidden.merchantId()))))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(paused.merchantId()))));
        }

        @Test
        void appointmentsAtTheirPlaceMatchTheCity() throws Exception {
            var shop = fx.provider("Fade Test", "trusted", List.of());
            fx.service(shop.merchantId(), BARBER, "Haircut", "fixed", 3500L, 30);

            mvc.perform(get("/api/v1/public/services/barber-and-hair/providers").param("city", "Calgary"))
                    .andExpect(jsonPath("$.kind").value("appointment"))
                    .andExpect(jsonPath("$.items[*].merchantId", hasItem(shop.merchantId())));
            mvc.perform(get("/api/v1/public/services/barber-and-hair/providers").param("city", "Edmonton"))
                    .andExpect(jsonPath("$.items[*].merchantId", not(hasItem(shop.merchantId()))));
        }

        @Test
        void locationIsValidated() throws Exception {
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers").param("lat", BELTLINE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("lng"))
                    .andExpect(jsonPath("$.errors[0].message").value("Send both lat and lng, or neither."));
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers")
                            .param("lat", "91")
                            .param("lng", BELTLINE_LNG))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("lat"))
                    .andExpect(jsonPath("$.errors[0].message").value("That location is outside the map."));
            mvc.perform(get("/api/v1/public/services/mobile-mechanic/providers").param("city", "x".repeat(61)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("At most 60 characters."));
        }
    }
}
