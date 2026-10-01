package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.merchants.application.RecheckRegistries;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-23: business registry lookups through the fixture adapters (the {@code test} profile's default; the real adapters
 * are covered by {@code RegistryAdaptersWireMockTest}). Evidence rows, manual reviews in the console queue, the
 * scheduled re-check and what reaches {@code ComplianceStatus}.
 */
class RegistryChecksApiTest extends IntegrationTest {

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ComplianceStatus compliance;

    @Autowired
    RecheckRegistries rechecks;

    OnboardingFlow flow;
    String user;
    String staff;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc);
        user = data.user("Owner");
        staff = data.user("Agent");
    }

    String applicant(String type, String businessJson) throws Exception {
        var id = flow.start(user, type);
        flow.business(id, user, businessJson).andExpect(status().isOk());
        return id;
    }

    String corp(String accessNumber) throws Exception {
        var id = flow.start(user, "provider");
        var doc = flow.upload(id, user, "legal");
        flow.business(
                        id,
                        user,
                        OnboardingFlow.corpBusiness(doc, OnboardingFlow.randomBn())
                                .replace("\"2201456789\"", "\"" + accessNumber + "\""))
                .andExpect(status().isOk());
        return id;
    }

    String check(String merchantId, String key) throws Exception {
        List<String> ids =
                JsonPath.read(flow.onboarding(merchantId, user), "$.checklist[?(@.key == '%s')].id".formatted(key));
        return ids.getFirst();
    }

    ResultActions lookUp(String merchantId, String key, String json) throws Exception {
        return flow.complete(merchantId, user, check(merchantId, key), json).andExpect(status().isOk());
    }

    Map<String, Object> evidence(String verificationId) {
        return jdbc.sql("""
                        select source, subject, query_number, outcome, array_to_string(reasons, ',') as reasons,
                               record_name, record_status, record_expires_on::text as expires, reference,
                               checked_at is not null as checked, review_state
                          from merchants.registry_checks where verification_id = ? order by checked_at desc limit 1""").params(verificationId).query().singleRow();
    }

    String reviewOf(String verificationId) {
        return jdbc.sql("select id from merchants.registry_checks where verification_id = ? and review_state = 'open'")
                .params(verificationId)
                .query(String.class)
                .single();
    }

    ResultActions decide(String reviewId, String json) throws Exception {
        return mvc.perform(post("/api/v1/console/registry-reviews/{id}/decision", reviewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)));
    }

    @Test
    void albertaCorporation_matchesTheRegistry_andKeepsTheEvidence() throws Exception {
        var id = corp("2201456789");
        lookUp(id, "registry", "{}")
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("verified"))
                .andExpect(jsonPath("$.checklist[?(@.key == 'registry')].reference")
                        .value("2201456789"));
        var ev = evidence(check(id, "registry"));
        assertThat(ev)
                .containsEntry("source", "alberta_corporate_registry")
                .containsEntry("subject", "corporation")
                .containsEntry("outcome", "matched")
                .containsEntry("record_name", "2201456 ALBERTA LTD.")
                .containsEntry("record_status", "Active")
                .containsEntry("reference", "fixtures:alberta_corporate_registry/2201456789")
                .containsEntry("checked", true);
        assertThat(ev.get("review_state")).isNull();
        assertThat(compliance.dueItems(id)).noneMatch(d -> d.checkType().equals("registry"));
    }

    @Test
    void unknownOrStruck_goToTheConsoleQueue_andAnAgentDecides() throws Exception {
        var unknown = corp("2000000001");
        lookUp(unknown, "registry", "{}")
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("submitted"));
        var struck = corp("2011111111");
        lookUp(struck, "registry", "{}");
        assertThat(evidence(check(struck, "registry")))
                .containsEntry("outcome", "mismatch")
                .containsEntry("reasons", "name,status")
                .containsEntry("review_state", "open");

        var reviewId = reviewOf(check(unknown, "registry"));
        mvc.perform(get("/api/v1/console/registry-reviews?limit=200")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].outcome".formatted(reviewId))
                        .value("not_found"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].checkKey".formatted(reviewId))
                        .value("registry"))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].businessName".formatted(reviewId))
                        .value("Aspen Wrench"));

        decide(
                        reviewId,
                        "{\"decision\":\"approve\",\"reference\":\"RA search 88120\",\"note\":\"Registry agent search\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("approved"));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", unknown).with(TestJwt.member(user)))
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("verified"))
                .andExpect(jsonPath("$.checklist[?(@.key == 'registry')].reference")
                        .value("RA search 88120"));
        decide(reviewId, "{\"decision\":\"reject\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("review_closed"));

        decide(reviewOf(check(struck, "registry")), "{\"decision\":\"reject\",\"note\":\"Struck off\"}")
                .andExpect(status().isOk());
        assertThat(compliance.dueItems(struck)).anySatisfy(d -> {
            assertThat(d.checkType()).isEqualTo("registry");
            assertThat(d.status()).isEqualTo("todo");
        });
    }

    @Test
    void soleProprietorWithoutTradeName_needsNoRegistration_withTradeNameIsLookedUp() throws Exception {
        var plain = applicant("provider", OnboardingFlow.soleBusiness("Pipes Co", "service.home-trades.plumber"));
        lookUp(plain, "registry", "{}")
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("verified"))
                .andExpect(jsonPath("$.checklist[?(@.key == 'registry')].reference")
                        .value("not_required"));

        var traded = applicant("provider", """
                {"displayName":"Pho Dau Bo","legalName":"Ravi Sandhu","structure":"sole",
                 "legalDetails":{"owner_legal_name":"Ravi Sandhu","trade_name":"Pho Dau Bo",
                   "trade_name_registration":"TN-2004-118840","sin_collected_by_stripe":true,
                   "address":"3715 17 Ave SE, Calgary AB"},
                 "categoryIds":["service.home-trades.plumber"]}""");
        lookUp(traded, "registry", "{}")
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("verified"));
        assertThat(evidence(check(traded, "registry"))).containsEntry("subject", "trade_name");
    }

    @Test
    void kitchen_alsoChecksTheCalgaryLicence_whoseExpiryFeedsCompliance() throws Exception {
        var id = applicant("kitchen", """
                {"displayName":"Pho Dau Bo","legalName":"Ravi Sandhu","structure":"sole",
                 "legalDetails":{"owner_legal_name":"Ravi Sandhu","trade_name":"Pho Dau Bo",
                   "trade_name_registration":"TN-2004-118840","sin_collected_by_stripe":true,
                   "address":"3715 17 Ave SE, Calgary AB"},
                 "profile":{"cityLicenceNumber":"BL 22-118840"},
                 "categoryIds":["food.format.restaurant-dine-in-and-takeout"]}""");
        lookUp(id, "registry", "{}")
                .andExpect(
                        jsonPath("$.checklist[?(@.key == 'registry')].status").value("verified"))
                .andExpect(jsonPath("$.checklist[?(@.key == 'registry')].expiresOn")
                        .value("2027-05-31"));
        assertThat(jdbc.sql(
                                "select count(*) from merchants.registry_checks where verification_id = ? and outcome = 'matched'")
                        .params(check(id, "registry"))
                        .query(Integer.class)
                        .single())
                .isEqualTo(2); // Alberta trade name + City of Calgary licence
        assertThat(jdbc.sql("select expires_at from merchants.verifications where id = ?")
                        .params(check(id, "registry"))
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(Instant.parse("2027-05-31T06:00:00Z")); // May 31 in Calgary, like uploaded expiry dates
    }

    @Test
    void licences_withoutAnApi_goToAnAgent_calgaryMobilePermit_isLookedUp() throws Exception {
        var provider = corp("2201456789");
        lookUp(provider, "licence:AMVIC", "{\"reference\":\"44812\"}")
                .andExpect(jsonPath("$.checklist[?(@.key == 'licence:AMVIC')].status")
                        .value("submitted"));
        var amvic = check(provider, "licence:AMVIC");
        assertThat(evidence(amvic)).containsEntry("source", "manual").containsEntry("outcome", "manual");
        decide(reviewOf(amvic), "{\"decision\":\"approve\",\"expiresOn\":\"2027-03-31\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", provider).with(TestJwt.member(user)))
                .andExpect(jsonPath("$.checklist[?(@.key == 'licence:AMVIC')].status")
                        .value("verified"))
                .andExpect(jsonPath("$.checklist[?(@.key == 'licence:AMVIC')].expiresOn")
                        .value("2027-03-31"));

        var truck = applicant("kitchen", OnboardingFlow.soleBusiness("Taco Truck Calgary", "food.format.food-truck"));
        lookUp(truck, "licence:Mobile permit", "{\"reference\":\"MV-2026-0042\"}")
                .andExpect(jsonPath("$.checklist[?(@.key == 'licence:Mobile permit')].status")
                        .value("verified"));
        assertThat(evidence(check(truck, "licence:Mobile permit")))
                .containsEntry("source", "calgary_business_licences")
                .containsEntry("expires", "2027-03-31");
    }

    @Test
    void scheduledRecheck_confirmsOrLapsesTheRow() throws Exception {
        var truck = applicant("kitchen", OnboardingFlow.soleBusiness("Taco Truck Calgary", "food.format.food-truck"));
        lookUp(truck, "licence:Mobile permit", "{\"reference\":\"MV-2026-0042\"}");
        var permit = check(truck, "licence:Mobile permit");
        var old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(45);
        jdbc.sql("update merchants.verifications set rechecked_at = ? where id = ?")
                .params(old, permit)
                .update();

        rechecks.recheckDue();
        assertThat(jdbc.sql(
                                "select count(*) from merchants.registry_checks where verification_id = ? and trigger = 'recheck'")
                        .params(permit)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(compliance.dueItems(truck)).noneMatch(d -> "Mobile permit".equals(d.registry()));

        // the city now lists the licence as expired
        jdbc.sql("update merchants.verifications set rechecked_at = ?, reference = 'BL 19-000042' where id = ?")
                .params(old, permit)
                .update();
        rechecks.recheckDue();
        assertThat(evidence(permit)).containsEntry("outcome", "mismatch").containsEntry("review_state", "open");
        assertThat(compliance.dueItems(truck)).anySatisfy(d -> {
            assertThat(d.registry()).isEqualTo("Mobile permit");
            assertThat(d.status()).isEqualTo("expired");
            assertThat(d.pausesAt()).isAfter(Instant.now().plusSeconds(14 * 86_400));
        });
    }

    @Test
    void theQueueIsForStaffWithMfa_andDecisionsAreValidated() throws Exception {
        mvc.perform(get("/api/v1/console/registry-reviews").with(TestJwt.member(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/registry-reviews").with(TestJwt.staffWithoutMfa(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        decide("01J9ZD3V0000000000000NOPE1", "{\"decision\":\"maybe\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("decision"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose approve or reject."));
        decide("01J9ZD3V0000000000000NOPE1", "{\"decision\":\"approve\"}").andExpect(status().isNotFound());
    }
}
