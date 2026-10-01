package ca.northline.merchants;

import static ca.northline.merchants.OnboardingFlow.soleBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-75: storefront visits (a daily count only — no cookie, IP, user agent or user is stored), the Studio's 30-day stats,
 * and the provider-funded reward.
 */
class StorefrontStatsApiTest extends IntegrationTest {

    static final String BROWSER = "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/141.0";

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    String owner;
    String merchantId;
    String slug;

    @BeforeEach
    void setUp() throws Exception {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        var flow = new OnboardingFlow(mvc);
        owner = data.user("Ravi");
        merchantId = flow.start(owner, "provider");
        flow.business(merchantId, owner, soleBusiness("Aspen Wrench", "service.automotive.mobile-mechanic"))
                .andExpect(status().isOk());
        slug = JsonPath.read(
                mvc.perform(get("/api/v1/merchants/{id}/storefront", merchantId).with(TestJwt.member(owner)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.slug");
    }

    void publish() throws Exception {
        jdbc.sql("update merchants.merchants set status = 'active' where id = ?")
                .param(merchantId)
                .update();
        mvc.perform(post("/api/v1/merchants/{id}/storefront/publish", merchantId)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
    }

    ResultActions visit(String userAgent) throws Exception {
        return mvc.perform(
                post("/api/v1/public/storefronts/{slug}/visits", slug).header("User-Agent", userAgent));
    }

    @Nested
    class Visits {

        @Test
        void countsPeople_notCrawlers_andKeepsOnlyTheNumber() throws Exception {
            publish();
            visit(BROWSER).andExpect(status().isNoContent());
            visit(BROWSER).andExpect(status().isNoContent());
            visit("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                    .andExpect(status().isNoContent());
            visit("facebookexternalhit/1.1").andExpect(status().isNoContent());
            mvc.perform(post("/api/v1/public/storefronts/{slug}/visits", slug)).andExpect(status().isNoContent());

            var rows = jdbc.sql("select * from merchants.storefront_visits where merchant_id = ?")
                    .param(merchantId)
                    .query()
                    .listOfRows();
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row).containsOnlyKeys("merchant_id", "day", "visits");
                assertThat(row.get("visits")).isEqualTo(2);
            });
        }

        @Test
        void unpublishedPagesAreNotCounted() throws Exception {
            visit(BROWSER).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/public/storefronts/{slug}/visits", "no-such-page")
                            .header("User-Agent", BROWSER))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Stats {

        @Test
        void visitsAndTheBookedRateOverThirtyDays() throws Exception {
            publish();
            for (int i = 0; i < 4; i++) {
                visit(BROWSER);
            }
            jdbc.sql("insert into merchants.storefront_visits (merchant_id, day, visits) values (?, ?, 6), (?, ?, 50)")
                    .params(
                            merchantId,
                            LocalDate.now(ZoneOffset.UTC).minusDays(10),
                            merchantId,
                            LocalDate.now(ZoneOffset.UTC).minusDays(45))
                    .update();
            var now = java.time.Instant.now().truncatedTo(ChronoUnit.SECONDS);
            for (var state : new String[] {"confirmed", "cancelled"}) {
                jdbc.sql("""
                                insert into booking.bookings (id, merchant_id, member_user_id, customer_id, type, state,
                                                              starts_at, ends_at, title, source)
                                values (?, ?, ?, ?, 'visit', ?, ?, ?, 'Brake inspection', 'studio')
                                """)
                        .params(
                                Ids.next(),
                                merchantId,
                                owner,
                                data.user("Amara Osei"),
                                state,
                                now.plus(2, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
                                now.plus(2, ChronoUnit.DAYS)
                                        .plus(1, ChronoUnit.HOURS)
                                        .atOffset(ZoneOffset.UTC))
                        .update();
            }

            var tech = data.user("Tech");
            data.member(merchantId, tech, MerchantRole.TECHNICIAN);
            mvc.perform(get("/api/v1/merchants/{id}/storefront-stats", merchantId)
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits").value(10)) // 4 today + 6 ten days ago; 45 days ago is outside
                    .andExpect(jsonPath("$.booked").value(1)) // the cancelled one doesn't count
                    .andExpect(jsonPath("$.bookedRateBps").value(1000))
                    .andExpect(jsonPath("$.daily.length()").value(30));
        }

        @Test
        void noVisitsHasNoRate_andOnlyMembersSeeIt() throws Exception {
            mvc.perform(get("/api/v1/merchants/{id}/storefront-stats", merchantId)
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.visits").value(0))
                    .andExpect(jsonPath("$.bookedRateBps").doesNotExist());
            mvc.perform(get("/api/v1/merchants/{id}/storefront-stats", merchantId)
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/merchants/{id}/storefront-stats", merchantId)
                            .with(TestJwt.memberWithoutMfa(owner)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class Reward {

        ResultActions putReward(String user, String json) throws Exception {
            return mvc.perform(put("/api/v1/merchants/{id}/reward", merchantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .with(TestJwt.member(user)));
        }

        String until(int days) {
            // the business's date is never more than a day from UTC's, so 10 days out is inside the 90-day horizon
            return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
        }

        @Test
        void theOwnerSwitchesItOn_thePublicPageShowsIt_andOffKeepsTheTerms() throws Exception {
            mvc.perform(get("/api/v1/merchants/{id}/reward", merchantId).with(TestJwt.member(owner)))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/public/merchants/{id}/reward", merchantId)).andExpect(status().isNoContent());

            putReward(owner, """
                            {"active":true,"multiplier":2,"label":"  brake jobs ","endsOn":"%s"}""".formatted(until(10)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.running").value(true))
                    .andExpect(jsonPath("$.label").value("brake jobs"));
            mvc.perform(get("/api/v1/public/merchants/{id}/reward", merchantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.multiplier").value(2))
                    .andExpect(jsonPath("$.label").value("brake jobs"))
                    .andExpect(jsonPath("$.endsOn").value(until(10)));

            putReward(owner, "{\"active\":false}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false))
                    .andExpect(jsonPath("$.running").value(false))
                    .andExpect(jsonPath("$.multiplier").value(2))
                    .andExpect(jsonPath("$.endsOn").value(until(10)));
            mvc.perform(get("/api/v1/public/merchants/{id}/reward", merchantId)).andExpect(status().isNoContent());

            var actions = jdbc.sql(
                            "select action from developer.audit_log where merchant_id = ? and action like 'reward.%' order by at")
                    .param(merchantId)
                    .query(String.class)
                    .list();
            assertThat(actions).containsExactly("reward.started", "reward.stopped");
        }

        @Test
        void theTermsAreValidated() throws Exception {
            putReward(owner, "{\"active\":true,\"multiplier\":5,\"endsOn\":\"%s\"}".formatted(until(10)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("multiplier"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose 2× or 3× points."));
            putReward(owner, "{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\"}".formatted(until(120)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Pick an end date within the next 90 days."));
            putReward(owner, "{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\"}".formatted(until(-3)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("endsOn"));
            putReward(owner, "{\"active\":true,\"multiplier\":3}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose when the reward ends."));
            putReward(
                            owner,
                            "{\"active\":true,\"multiplier\":3,\"label\":\"%s\",\"endsOn\":\"%s\"}"
                                    .formatted("x".repeat(61), until(10)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("At most 60 characters."));
            putReward(owner, "{\"multiplier\":3}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Say whether the reward is on."));
        }

        @Test
        void onlyTheOwnerFundsIt_membersSeeIt() throws Exception {
            var tech = data.user("Tech");
            data.member(merchantId, tech, MerchantRole.TECHNICIAN);
            putReward(tech, "{\"active\":true,\"multiplier\":2,\"endsOn\":\"%s\"}".formatted(until(10)))
                    .andExpect(status().isForbidden());
            putReward(owner, "{\"active\":true,\"multiplier\":3,\"endsOn\":\"%s\"}".formatted(until(10)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchants/{id}/reward", merchantId).with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.multiplier").value(3));
            mvc.perform(get("/api/v1/merchants/{id}/reward", merchantId).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
        }
    }
}
