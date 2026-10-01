package ca.northline.region;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** S-47: the Location screen's endpoints with the local fixture addresses (PLACES_PROVIDER=local under test). */
class GeoApiTest extends IntegrationTest {

    static final String SESSION = "5f2c9a0e-2b7c-4d7e-9a51-0c1e4f3a8b21";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    /**
     * Markets and zones are region data, not migrations: the tests load the local dev seed's (V119, idempotent). The
     * provinces served come from the test profile's SEARCH_MARKETS default (AB, BC, ON, QC).
     */
    @BeforeEach
    void markets() {
        new ResourceDatabasePopulator(new ClassPathResource("db/seed-dev/V119__dev_markets.sql")).execute(dataSource);
    }

    /** A fresh browsing session per test, so the per-session lookup limit never spills over. */
    static String guest() {
        return "g_" + Ids.next();
    }

    @Nested
    class Markets {
        @Test
        void listsTheServedProvincesFirstWithTheirMarketsTaxAndTheFallbackMarket() throws Exception {
            mvc.perform(get("/api/v1/geo/markets"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=300, public"))
                    .andExpect(jsonPath("$.items[0].code").value("AB"))
                    .andExpect(jsonPath("$.items[0].name").value("Alberta"))
                    .andExpect(jsonPath("$.items[0].stage").value("live"))
                    .andExpect(jsonPath("$.items[0].taxBps").value(500))
                    .andExpect(jsonPath("$.items[0].markets[0].city").value("Calgary"))
                    .andExpect(jsonPath("$.items[0].markets[0].lat").value(51.0447))
                    .andExpect(jsonPath("$.items[0].markets[1].city").value("Edmonton"))
                    .andExpect(jsonPath("$.items[0].markets[2].city").value("Airdrie"))
                    .andExpect(jsonPath("$.items[0].markets[3].stage").value("pilot"))
                    // served by configuration (SEARCH_MARKETS), whatever the row says; its markets keep their stage
                    .andExpect(jsonPath("$.items[1].code").value("BC"))
                    .andExpect(jsonPath("$.items[1].stage").value("live"))
                    .andExpect(jsonPath("$.items[1].markets[0].stage").value("pilot"))
                    .andExpect(jsonPath("$.items[2].code").value("ON"))
                    .andExpect(jsonPath("$.items[3].code").value("QC"))
                    .andExpect(jsonPath("$.fallback.city").value("Calgary"))
                    .andExpect(jsonPath("$.items[*].code", not(hasItem("YT"))));
            mvc.perform(get("/api/v1/geo/markets").header("Accept-Language", "fr-CA"))
                    .andExpect(jsonPath("$.items[1].name").value("Colombie-Britannique"));
        }

        @Test
        void aProvinceNotServedShowsItsOwnStageAfterTheServedOnes() throws Exception {
            jdbc.sql("update region.regions set stage = 'waitlist' where id = 'prov-mb'")
                    .update();
            try {
                mvc.perform(get("/api/v1/geo/markets"))
                        .andExpect(jsonPath("$.items[4].code").value("MB"))
                        .andExpect(jsonPath("$.items[4].stage").value("waitlist"))
                        .andExpect(jsonPath("$.items[4].markets", hasSize(0)));
            } finally {
                jdbc.sql("update region.regions set stage = 'off' where id = 'prov-mb'")
                        .update();
            }
        }
    }

    @Nested
    class Autocomplete {
        @Test
        void suggestsCanadianAddressesForGuests() throws Exception {
            mvc.perform(get("/api/v1/geo/autocomplete")
                            .param("q", "1204 17 Ave")
                            .param("session", SESSION)
                            .param("lat", "51.04")
                            .param("lng", "-114.07")
                            .header("X-Northline-Guest", guest()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(3)))
                    .andExpect(jsonPath("$.items[*].main", hasItem("1204 17 Ave SW")))
                    .andExpect(jsonPath("$.items[0].secondary").value("Calgary, AB T2T 0B7, Canada"))
                    .andExpect(jsonPath("$.attribution").value("Northline test addresses"));
        }

        @Test
        void waitsForThreeCharactersAndChecksInput() throws Exception {
            mvc.perform(get("/api/v1/geo/autocomplete").param("q", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
            mvc.perform(get("/api/v1/geo/autocomplete").param("q", "1".repeat(201)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].field").value("q"))
                    .andExpect(jsonPath("$.errors[0].message").value("At most 200 characters."));
            mvc.perform(get("/api/v1/geo/autocomplete").param("q", "1204").param("session", "bad token!"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].field").value("session"))
                    .andExpect(jsonPath("$.errors[0].message").value("Start the address search again."));
        }

        @Test
        void limitsLookupsPerBrowsingSession() throws Exception {
            var g = guest();
            for (int i = 0; i < 60; i++) {
                mvc.perform(get("/api/v1/geo/autocomplete").param("q", "queen").header("X-Northline-Guest", g))
                        .andExpect(status().isOk());
            }
            mvc.perform(get("/api/v1/geo/autocomplete").param("q", "queen").header("X-Northline-Guest", g))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.code").value("rate_limited"));
            mvc.perform(get("/api/v1/geo/autocomplete").param("q", "queen").header("X-Northline-Guest", guest()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class ChosenAddress {
        @Test
        void resolvesToTheMarketAndZone() throws Exception {
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJfake-yyc-beltline")
                            .param("session", SESSION)
                            .header("X-Northline-Guest", guest()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("1204 17 Ave SW, Calgary"))
                    .andExpect(jsonPath("$.street").value("1204 17 Ave SW"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.province").value("AB"))
                    .andExpect(jsonPath("$.postalCode").value("T2T 0B7"))
                    .andExpect(jsonPath("$.lat").value(51.0379))
                    .andExpect(jsonPath("$.resolution.market.id").value("mkt-calgary"))
                    .andExpect(jsonPath("$.resolution.market.stage").value("live"))
                    .andExpect(jsonPath("$.resolution.zone.name").value("Beltline"))
                    .andExpect(jsonPath("$.resolution.zone.runsPerDay").value(3))
                    .andExpect(jsonPath("$.resolution.waitlist").value(nullValue()));
        }

        @Test
        void airdrieIsItsOwnMarketThoughCalgaryIsNear() throws Exception {
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJfake-airdrie").header("X-Northline-Guest", guest()))
                    .andExpect(jsonPath("$.resolution.market.city").value("Airdrie"))
                    .andExpect(jsonPath("$.resolution.zone.name").value("Airdrie"));
        }

        @Test
        void outsideALiveMarketPointsToItsWaitlist() throws Exception {
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJfake-lethbridge").header("X-Northline-Guest", guest()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("910 4 Ave S, Lethbridge"))
                    .andExpect(jsonPath("$.resolution.market.stage").value("waitlist"))
                    .andExpect(jsonPath("$.resolution.zone").value(nullValue()))
                    .andExpect(jsonPath("$.resolution.waitlist.regionId").value("mkt-lethbridge"));
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJfake-toronto").header("X-Northline-Guest", guest()))
                    .andExpect(jsonPath("$.resolution.market").value(nullValue()))
                    .andExpect(jsonPath("$.resolution.waitlist.regionId").value("prov-on"))
                    .andExpect(jsonPath("$.resolution.waitlist.name").value("Ontario"));
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJfake-vancouver").header("X-Northline-Guest", guest()))
                    .andExpect(jsonPath("$.resolution.market.stage").value("pilot"))
                    .andExpect(jsonPath("$.resolution.waitlist.regionId").value("mkt-vancouver"));
        }

        @Test
        void anUnknownPlaceIs404() throws Exception {
            mvc.perform(get("/api/v1/geo/places/{id}", "ChIJnothing").header("X-Northline-Guest", guest()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Reverse {
        @Test
        void namesTheNeighbourhoodInItsMarket() throws Exception {
            mvc.perform(get("/api/v1/geo/reverse")
                            .param("lat", "51.03800")
                            .param("lng", "-114.08900")
                            .header("X-Northline-Guest", guest()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("Beltline, Calgary"))
                    .andExpect(jsonPath("$.city").value("Calgary"))
                    .andExpect(jsonPath("$.market.stage").value("live"))
                    .andExpect(jsonPath("$.zone.name").value("Beltline"));
        }

        @Test
        void usesTheZoneWhenThePlacesProviderKnowsNothingThere() throws Exception {
            // Old Strathcona has a zone but no fixture address within 3 km of this point
            mvc.perform(get("/api/v1/geo/reverse").param("lat", "53.5150").param("lng", "-113.5100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.label").value("Old Strathcona, Edmonton"))
                    .andExpect(jsonPath("$.city").value("Edmonton"));
        }

        @Test
        void nothingThereIs404AndCoordinatesAreChecked() throws Exception {
            mvc.perform(get("/api/v1/geo/reverse").param("lat", "60").param("lng", "-100"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/geo/reverse").param("lat", "91").param("lng", "-100"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].field").value("lat"))
                    .andExpect(jsonPath("$.errors[0].message").value("Latitude must be between -90 and 90."));
        }

        @Test
        void resolveGivesMarketZoneAndWaitlistForAPoint() throws Exception {
            mvc.perform(get("/api/v1/geo/resolve").param("lat", "52.2681").param("lng", "-113.8112"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.market.city").value("Red Deer"))
                    .andExpect(jsonPath("$.market.stage").value("pilot"))
                    .andExpect(jsonPath("$.waitlist.regionId").value("mkt-red-deer"));
        }
    }

    @Nested
    class Waitlist {
        private int rows(String regionId, String email) {
            return jdbc.sql("select count(*) from region.waitlist where region_id = ? and lower(email) = lower(?)")
                    .params(regionId, email)
                    .query(Integer.class)
                    .single();
        }

        @Test
        void aGuestJoinsWithTheirEmailOnce() throws Exception {
            var email = "dana." + Ids.next().toLowerCase(Locale.ROOT) + "@example.ca";
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-lethbridge\",\"email\":\"" + email + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.joined").value(true));
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-lethbridge\",\"email\":\"" + email.toUpperCase(Locale.ROOT)
                                    + "\"}"))
                    .andExpect(status().isOk());
            org.assertj.core.api.Assertions.assertThat(rows("mkt-lethbridge", email))
                    .isEqualTo(1);
        }

        @Test
        void aSignedInPersonJoinsWithoutAnEmail() throws Exception {
            var user = data.user("Amara Osei");
            mvc.perform(post("/api/v1/geo/waitlist")
                            .with(TestJwt.customer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"prov-on\"}"))
                    .andExpect(status().isCreated());
            var stored = jdbc.sql("select count(*) from region.waitlist where user_id = ? and email is null")
                    .param(user)
                    .query(Integer.class)
                    .single();
            org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(1);
        }

        @Test
        void checksTheRegionAndTheEmail() throws Exception {
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-lethbridge\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].field").value("email"))
                    .andExpect(jsonPath("$.errors[0].message").value("Email is required."));
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-lethbridge\",\"email\":\"dana@\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].message").value("That doesn't look like an email address."));
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-nowhere\",\"email\":\"dana@example.ca\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].field").value("regionId"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose where you’d like Northline."));
            mvc.perform(post("/api/v1/geo/waitlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionId\":\"mkt-calgary\",\"email\":\"dana@example.ca\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("region_live"));
        }
    }
}
