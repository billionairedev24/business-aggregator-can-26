package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-24: linking the payout bank account through Stripe Financial Connections (the local fake: a simulated bank picker's
 * token), only the institution's name and last 4 kept, every step in the audit log with the step-up, the
 * {@code financial_connections.account.disconnected} webhook, manual entry still available, and onboarding's bank
 * check verified once a bank is linked.
 */
@Import({PaymentsFixture.class, SettingsFixtures.class})
class BankLinkingApiTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    SettingsFixtures settings;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    JdbcClient jdbc;

    PaymentsFixture.Shop shop;

    @BeforeEach
    void shop() {
        shop = fx.shop("provider", "master");
    }

    private String url(String path) {
        return "/api/v1/merchants/" + shop.merchantId() + "/payouts" + path;
    }

    private String linkInstantly(String fca) throws Exception {
        var body = mvc.perform(post(url("/bank-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"instant","linkedAccount":"btok_local_003_8820","financialConnectionsAccount":"%s"}""".formatted(fca))
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("instant"))
                .andExpect(jsonPath("$.label").value("RBC ··8820"))
                .andExpect(jsonPath("$.institutionName").value("RBC"))
                .andExpect(jsonPath("$.last4").value("8820"))
                .andExpect(jsonPath("$.holderName").value("Prairie Wrench Automotive Ltd."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private long audits(String targetId, String action) {
        return jdbc.sql(
                        "select count(*) from developer.audit_log where target_id = ? and action = ? and merchant_id = ?")
                .params(targetId, action, shop.merchantId())
                .query(Long.class)
                .single();
    }

    @Test
    void session_isTheFakeWithoutStripe_ownerOnly_andNeedsMfa() throws Exception {
        mvc.perform(post(url("/bank-accounts/link-session")).with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("fake"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
        mvc.perform(post(url("/bank-accounts/link-session"))
                        .with(TestJwt.member(fx.member(shop, MerchantRole.BOOKKEEPER))))
                .andExpect(status().isForbidden());
        mvc.perform(post(url("/bank-accounts/link-session")).with(TestJwt.memberWithoutMfa(shop.ownerId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(post(url("/bank-accounts/link-session")).with(TestJwt.member(Ids.next())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
    }

    @Test
    void instantLink_keepsOnlyTheNameAndLast4_andIsAudited_throughConfirmAndTakeOver() throws Exception {
        var fca = "fca_local_" + Ids.next();
        var id = linkInstantly(fca);
        var stored = jdbc.sql("""
                        select coalesce(institution_number, '') || '|' || coalesce(transit_number, '') || '|'
                               || financial_connections_account from payments.payout_accounts where id = ?""").params(id).query(String.class).single();
        assertThat(stored).isEqualTo("||" + fca);
        assertThat(audits(id, "payout_account.linked")).isEqualTo(1);
        assertThat(jdbc.sql("select after::text from developer.audit_log where target_id = ? and action = ?")
                        .params(id, "payout_account.linked")
                        .query(String.class)
                        .single())
                .contains("\"last4\": \"8820\"")
                .contains("\"institution\": \"RBC\"");

        // no second factor proof → 403 and nothing recorded
        mvc.perform(post(url("/bank-accounts/" + id + "/confirm"))
                        .header("Idempotency-Key", Ids.next())
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("step_up_required"));
        assertThat(audits(id, "payout_account.change_confirmed")).isZero();

        mvc.perform(post(url("/bank-accounts/" + id + "/confirm"))
                        .header("Idempotency-Key", Ids.next())
                        .header("X-Step-Up", "dev")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"));
        assertThat(jdbc.sql("""
                        select actor_id || '|' || role || '|' || (after ->> 'stepUp') || '|' || (before ->> 'last4')
                          from developer.audit_log where target_id = ? and action = 'payout_account.change_confirmed'""").params(id).query(String.class).single())
                .isEqualTo(shop.ownerId() + "|owner|true|3391");

        jdbc.sql("update payments.payout_accounts set effective_at = now() - interval '1 minute' where id = ?")
                .params(id)
                .update();
        jobs.activatePayoutAccounts();
        assertThat(audits(id, "payout_account.change_effective")).isEqualTo(1);
        mvc.perform(get(url("/overview")).with(TestJwt.member(shop.ownerId())))
                .andExpect(jsonPath("$.account.label").value("RBC ··8820"))
                .andExpect(jsonPath("$.account.method").value("instant"))
                .andExpect(jsonPath("$.account.disconnectedAt").doesNotExist());
    }

    @Test
    void aLinkThatCantBeUsed_asksToConnectAgain() throws Exception {
        mvc.perform(post(url("/bank-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"instant\",\"linkedAccount\":\"btok_somebody_else\"}")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("linkedAccount"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("We couldn't use that bank link. Connect your bank again."));
        mvc.perform(post(url("/bank-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"instant","linkedAccount":"btok_local_003_8820","financialConnectionsAccount":"fca_live_x"}""")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("We couldn't use that bank link. Connect your bank again."));
        mvc.perform(post(url("/bank-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"instant\",\"linkedAccount\":\"btok_local_003_8820\"}")
                        .with(TestJwt.member(fx.member(shop, MerchantRole.TECHNICIAN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualEntry_isStillAvailable_andAudited() throws Exception {
        var body = mvc.perform(post(url("/bank-accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"manual","institution":"004","transit":"12345","accountNumber":"0012347777",
                                 "holderName":"Prairie Wrench Automotive Ltd."}""")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("manual"))
                .andExpect(jsonPath("$.label").value("TD ··7777"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(body, "$.id");
        assertThat(audits(id, "payout_account.linked")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from developer.audit_log where merchant_id = ? and after::text like ?")
                        .params(shop.merchantId(), "%0012347777%")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void disconnectedWebhook_marksTheLink_once_andPayoutsKeepTheAccount() throws Exception {
        var fca = "fca_local_" + Ids.next();
        var id = linkInstantly(fca);
        mvc.perform(post(url("/bank-accounts/" + id + "/confirm"))
                        .header("Idempotency-Key", Ids.next())
                        .header("X-Step-Up", "dev")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk());
        var object = """
                {"id":"%s","object":"financial_connections.account","status":"disconnected",\
                "account_holder":{"type":"account","account":"acct_%s"}}""".formatted(fca, shop.merchantId());
        for (var i = 0; i < 2; i++) { // Stripe retries: the second delivery is another event, applied once
            var payload = StripeWebhookApiTest.event(
                    "evt_" + Ids.next(),
                    "financial_connections.account.disconnected",
                    Instant.now(),
                    object,
                    null,
                    false);
            mvc.perform(post("/api/v1/webhooks/stripe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .header(
                                    "Stripe-Signature",
                                    StripeWebhookApiTest.sign(
                                            payload, StripeWebhookApiTest.PLATFORM_SECRET, Instant.now())))
                    .andExpect(status().isOk());
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobs.processStripeEvents();
            assertThat(audits(id, "payout_account.bank_connection_ended")).isEqualTo(1);
        });
        mvc.perform(get(url("/overview")).with(TestJwt.member(shop.ownerId())))
                .andExpect(jsonPath("$.pendingAccount.label").value("RBC ··8820"))
                .andExpect(jsonPath("$.pendingAccount.disconnectedAt").exists());
    }

    @Test
    void disconnectedWebhook_forAnAccountNorthlineDoesntKnow_isIgnored() throws Exception {
        var eventId = "evt_" + Ids.next();
        var payload = StripeWebhookApiTest.event(
                eventId,
                "financial_connections.account.disconnected",
                Instant.now(),
                "{\"id\":\"fca_unknown_" + Ids.next() + "\",\"object\":\"financial_connections.account\"}",
                null,
                false);
        mvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header(
                                "Stripe-Signature",
                                StripeWebhookApiTest.sign(
                                        payload, StripeWebhookApiTest.PLATFORM_SECRET, Instant.now())))
                .andExpect(status().isOk());
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobs.processStripeEvents();
            assertThat(jdbc.sql("select state from payments.stripe_events where id = ?")
                            .params(eventId)
                            .query(String.class)
                            .single())
                    .isEqualTo("ignored");
        });
    }

    @Test
    void onboardingBankCheck_isVerified_whenABankIsConfirmed() throws Exception {
        var check = settings.verification(shop.merchantId(), "bank", null, null, "submitted", null, "bank");
        var id = linkInstantly("fca_local_" + Ids.next());
        mvc.perform(post(url("/bank-accounts/" + id + "/confirm"))
                        .header("Idempotency-Key", Ids.next())
                        .header("X-Step-Up", "dev")
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.sql(
                                        "select status || '|' || coalesce(reference, '') from merchants.verifications where id = ?")
                                .params(check)
                                .query(String.class)
                                .single())
                        .isEqualTo("verified|TD ··3391"));
    }
}
