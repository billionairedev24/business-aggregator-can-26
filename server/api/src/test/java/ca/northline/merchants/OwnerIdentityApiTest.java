package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-22: identity verification of the owners ≥ 25 % with Stripe Identity. Sessions come from the fake adapter (the
 * {@code test} profile's {@code IDENTITY_PROVIDER=local}); Stripe's webhooks are delivered signed to the real
 * platform endpoint (S-12 verification + dedupe) with the test profile's obviously fake secret.
 */
class OwnerIdentityApiTest extends IntegrationTest {

    static final String PLATFORM_SECRET = "whsec_test_platform_fake";

    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DevIdentityOutcomes fake;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    ComplianceStatus compliance;

    OnboardingFlow flow;
    String user;
    String merchantId;
    String ravi;
    String priya;

    @BeforeEach
    void corporationWithTwoOwners() throws Exception {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        flow = new OnboardingFlow(mvc, dataSource, fake);
        user = data.user("Ravi Sandhu");
        merchantId = flow.start(user, "provider");
        var doc = flow.upload(merchantId, user, "legal");
        flow.business(merchantId, user, OnboardingFlow.corpBusiness(doc, OnboardingFlow.randomBn()))
                .andExpect(status().isOk());
        ravi = principal("Ravi Sandhu");
        priya = principal("Priya Sandhu");
    }

    String principal(String name) {
        return jdbc.sql("select id from merchants.merchant_principals where merchant_id = ? and legal_name = ?")
                .params(merchantId, name)
                .query(String.class)
                .single();
    }

    String session(String principalId) {
        return jdbc.sql("select stripe_session from merchants.owner_identity_checks where principal_id = ?")
                .params(principalId)
                .query(String.class)
                .single();
    }

    String kyc() throws Exception {
        return JsonPath.read(flow.onboarding(merchantId, user), "$.checklist[?(@.key == 'kyc')].status")
                .toString();
    }

    ResultActions owners() throws Exception {
        return mvc.perform(
                get("/api/v1/merchants/{id}/identity-checks", merchantId).with(TestJwt.member(user)));
    }

    ResultActions start(String principalId, String json) throws Exception {
        return flow.startSession(merchantId, user, principalId, json);
    }

    static final String SELF = "{\"delivery\":\"self\"}";
    static final String EMAIL = "{\"delivery\":\"email\",\"email\":\"Priya@Example.test\"}";

    // ── Stripe webhooks ──────────────────────────────────────────────────────────────────────────────────────────

    String webhook(String sessionId, String type, String status, @Nullable String lastError, Instant created)
            throws Exception {
        var eventId = "evt_" + Ids.next();
        var object = """
                {"id":"%s","object":"identity.verification_session","status":"%s","type":"document",
                 "client_reference_id":"x","livemode":false,"url":null,"verified_outputs":null,
                 "last_error":%s,"metadata":{"northline_merchant_id":"%s"}}""".formatted(
                        sessionId,
                        status,
                        lastError == null ? "null" : "{\"code\":\"" + lastError + "\",\"reason\":\"…\"}",
                        merchantId);
        var payload = """
                {"id":"%s","object":"event","api_version":"2026-08-26.dahlia","created":%d,"livemode":false,\
                "type":"%s","pending_webhooks":1,"request":{"id":null,"idempotency_key":null},\
                "data":{"object":%s}}""".formatted(eventId, created.getEpochSecond(), type, object);
        deliver(payload);
        return payload;
    }

    void deliver(String payload) throws Exception {
        mvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", sign(payload)))
                .andExpect(status().isOk());
        jobs.processStripeEvents();
    }

    static String sign(String payload) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PLATFORM_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var t = Instant.now().getEpochSecond();
        return "t=" + t + ",v1="
                + HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }

    String statusOf(String principalId) {
        return jdbc.sql("select status from merchants.owner_identity_checks where principal_id = ?")
                .params(principalId)
                .query(String.class)
                .single();
    }

    void awaitStatus(String principalId, String expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(principalId)).isEqualTo(expected));
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void listsEveryOwnerAtOrAbove25Percent_notStarted() throws Exception {
        owners().andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[*].legalName", org.hamcrest.Matchers.contains("Ravi Sandhu", "Priya Sandhu")))
                .andExpect(jsonPath(
                        "$.items[*].status", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("not_started"))))
                .andExpect(jsonPath("$.items[0].ownershipPct").value(60));
        mvc.perform(get("/api/v1/merchants/{id}/onboarding", merchantId).with(TestJwt.member(user)))
                .andExpect(jsonPath("$.checklist[0].key").value("kyc"))
                .andExpect(jsonPath("$.checklist[0].action").value("identity"))
                .andExpect(jsonPath("$.checklist[0].status").value("todo"));
    }

    @Test
    void signedInOwner_getsStripesHostedFlow_andBecomesYou() throws Exception {
        start(ravi, SELF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url")
                        .value(org.hamcrest.Matchers.containsString("/api/v1/dev/identity-sessions/vs_fake_")))
                .andExpect(jsonPath("$.owner.you").value(true))
                .andExpect(jsonPath("$.owner.status").value("pending"))
                .andExpect(jsonPath("$.owner.delivery").value("self"));
        assertThat(jdbc.sql("select user_id from merchants.merchant_principals where id = ?")
                        .params(ravi)
                        .query(String.class)
                        .single())
                .isEqualTo(user);
        // the same person can't also be the other owner
        start(priya, SELF)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("identity_already_you"));
    }

    @Test
    void otherOwners_getTheLinkByEmail_inTheRequestersLanguage() throws Exception {
        var address = "priya-" + Ids.next().toLowerCase(java.util.Locale.ROOT) + "@example.test";
        start(priya, "{\"delivery\":\"email\",\"email\":\"%s\"}".formatted(address.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.owner.emailMasked").value("p***@example.test"))
                .andExpect(jsonPath("$.owner.you").value(false));
        var session = session(priya);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(emails.to(address))
                        .singleElement()
                        .satisfies(m -> {
                            assertThat(m.subject()).startsWith("Verify your identity for");
                            assertThat(m.text())
                                    .contains(
                                            "Priya Sandhu", "Aspen Wrench", "/api/v1/dev/identity-sessions/" + session);
                        }));
    }

    @Test
    void emailIsRequiredAndChecked_deliveryMustBeKnown() throws Exception {
        start(priya, "{\"delivery\":\"email\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter the owner's email address."));
        start(priya, "{\"delivery\":\"email\",\"email\":\"priya@\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That doesn't look like an email address."));
        start(priya, "{\"delivery\":\"sms\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("delivery"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose how this owner verifies."));
        start(Ids.next(), SELF).andExpect(status().isNotFound());
    }

    @Test
    void webhooks_moveOwnersAndTheKycRow_andFeedComplianceStatus() throws Exception {
        start(ravi, SELF).andExpect(status().isOk());
        start(priya, EMAIL).andExpect(status().isOk());
        var t0 = Instant.now().minusSeconds(60);
        assertThat(compliance.dueItems(merchantId)).anySatisfy(d -> {
            assertThat(d.checkType()).isEqualTo("kyc");
            assertThat(d.status()).isEqualTo("todo");
        });

        webhook(session(ravi), "identity.verification_session.verified", "verified", null, t0);
        awaitStatus(ravi, "verified");
        assertThat(kyc()).contains("todo");

        webhook(session(priya), "identity.verification_session.processing", "processing", null, t0);
        awaitStatus(priya, "processing");
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(kyc()).contains("submitted"));
        assertThat(compliance.dueItems(merchantId)).noneMatch(d -> d.checkType().equals("kyc"));

        webhook(session(priya), "identity.verification_session.verified", "verified", null, t0.plusSeconds(5));
        awaitStatus(priya, "verified");
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(kyc()).contains("verified"));
        owners().andExpect(jsonPath(
                "$.items[*].nameMatch", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("match"))));
        // only the status, session id and match results are kept
        assertThat(jdbc.sql("select count(*) from merchants.owner_identity_checks where merchant_id = ? and "
                                + "status = 'verified' and name_match = 'match' and stripe_session like 'vs_%'")
                        .params(merchantId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void nameMismatch_goesToManualReview_andCountsAsHandedIn() throws Exception {
        start(ravi, SELF).andExpect(status().isOk());
        assertThat(fake.prepare(session(ravi), "name_mismatch")).isPresent();
        webhook(session(ravi), "identity.verification_session.verified", "verified", null, Instant.now());
        awaitStatus(ravi, "review");
        owners().andExpect(jsonPath("$.items[0].nameMatch").value("mismatch"));
        start(ravi, SELF)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("identity_in_review"));
    }

    @Test
    void requiresInput_letsTheOwnerRetry_withANewSession() throws Exception {
        start(ravi, SELF).andExpect(status().isOk());
        var first = session(ravi);
        webhook(
                first,
                "identity.verification_session.requires_input",
                "requires_input",
                "document_expired",
                Instant.now());
        awaitStatus(ravi, "retry");
        owners().andExpect(jsonPath("$.items[0].lastError").value("document_expired"));

        start(ravi, SELF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.attempts").value(2));
        assertThat(session(ravi)).isNotEqualTo(first);
        // a late event for the replaced session changes nothing
        webhook(first, "identity.verification_session.verified", "verified", null, Instant.now());
        assertThat(statusOf(ravi)).isEqualTo("pending");
    }

    @Test
    void duplicateAndOutOfOrderDeliveries_areAppliedOnce() throws Exception {
        start(ravi, SELF).andExpect(status().isOk());
        var now = Instant.now();
        var verified = webhook(session(ravi), "identity.verification_session.verified", "verified", null, now);
        awaitStatus(ravi, "verified");
        deliver(verified); // Stripe retries the same event
        // an older requires_input arrives late
        webhook(
                session(ravi),
                "identity.verification_session.requires_input",
                "requires_input",
                "selfie_face_mismatch",
                now.minusSeconds(30));
        assertThat(statusOf(ravi)).isEqualTo("verified");
        start(ravi, SELF)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("identity_already_verified"));
    }

    @Test
    void ownersKeepTheirCheck_whenTheBusinessStepIsSavedAgain_andANewOwnerReopensKyc() throws Exception {
        flow.verifyOwners(merchantId, user, "verified");
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(kyc()).contains("verified"));
        var doc = flow.upload(merchantId, user, "legal");
        var bn = jdbc.sql("select business_number from merchants.merchants where id = ?")
                .params(merchantId)
                .query(String.class)
                .single();
        var withThird = OnboardingFlow.corpBusiness(doc, bn)
                .replace(
                        "{\"legalName\":\"Priya Sandhu\",\"role\":\"shareholder\",\"ownershipPct\":40}",
                        "{\"legalName\":\"Priya Sandhu\",\"role\":\"shareholder\",\"ownershipPct\":30},"
                                + "{\"legalName\":\"Jas Gill\",\"role\":\"shareholder\",\"ownershipPct\":10}");
        flow.business(merchantId, user, withThird).andExpect(status().isOk());
        assertThat(principal("Priya Sandhu")).isEqualTo(priya);
        assertThat(kyc()).contains("verified"); // Jas holds 10 %: no verification needed

        var withNewOwner = withThird
                .replace("\"ownershipPct\":10", "\"ownershipPct\":25")
                .replace("\"ownershipPct\":60", "\"ownershipPct\":45");
        flow.business(merchantId, user, withNewOwner).andExpect(status().isOk());
        assertThat(kyc()).contains("todo");
        owners().andExpect(
                        jsonPath("$.items[?(@.legalName == 'Jas Gill')].status").value("not_started"));
    }

    @Test
    void kycRowCannotBeCompletedDirectly() throws Exception {
        String kycId = JsonPath.read(flow.onboarding(merchantId, user), "$.checklist[0].id");
        flow.complete(merchantId, user, kycId, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("identity_per_owner"));
    }

    @Test
    void ownersOnly_withMfa() throws Exception {
        var outsider = data.user("Outsider");
        mvc.perform(get("/api/v1/merchants/{id}/identity-checks", merchantId).with(TestJwt.member(outsider)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(get("/api/v1/merchants/{id}/identity-checks", merchantId).with(TestJwt.memberWithoutMfa(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        var tech = data.user("Tech");
        data.member(merchantId, tech, MerchantRole.TECHNICIAN);
        mvc.perform(post("/api/v1/merchants/{id}/identity-checks/{p}/session", merchantId, ravi)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELF)
                        .with(TestJwt.member(tech)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
    }
}
