package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.api.PayoutAccountChanged;
import ca.northline.payments.api.PayoutSent;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Payouts: overview, history, instant payout (idempotency + step-up), schedule, bank account change. */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class PayoutApiTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsJobs jobs;

    PaymentsFixture.Shop shop;

    @BeforeEach
    void seed() {
        shop = fx.shop("provider", "master");
        fx.escrow(
                shop.merchantId(),
                "service",
                "released",
                90_400,
                900,
                "Brake inspection",
                "K. Ng",
                hoursAgo(60),
                hoursAgo(10));
    }

    private MockHttpServletRequestBuilder instant(String body, String key, String proof) {
        var req = post("/api/v1/merchants/{id}/payouts/instant", shop.merchantId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.member(shop.ownerId()));
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        if (proof != null) {
            req.header("X-Step-Up", proof);
        }
        return req;
    }

    @Test
    void overview_availableNextPayoutBankAndInstantTerms() throws Exception {
        mvc.perform(get("/api/v1/merchants/{id}/payouts/overview", shop.merchantId())
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCents").value(82_264))
                .andExpect(jsonPath("$.payableCents").value(82_264))
                .andExpect(jsonPath("$.schedule.frequency").value("weekly"))
                .andExpect(jsonPath("$.schedule.weekday").value(5))
                .andExpect(jsonPath("$.schedule.reserve").value("none"))
                .andExpect(jsonPath("$.account.label").value("TD ··3391"))
                .andExpect(jsonPath("$.account.holderName").value("Prairie Wrench Automotive Ltd."))
                .andExpect(jsonPath("$.pendingAccount").doesNotExist())
                .andExpect(jsonPath("$.instant.eligible").value(true))
                .andExpect(jsonPath("$.instant.feeBps").value(100))
                .andExpect(jsonPath("$.instant.minFeeCents").value(50));
    }

    @Nested
    class InstantPayout {

        @Test
        void ownerPaysOut_feeDeducted_ledgerDebited_eventPublished() throws Exception {
            mvc.perform(instant("{\"amountCents\":10000}", Ids.next(), "dev"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reference", startsWith("po_")))
                    .andExpect(jsonPath("$.kind").value("instant"))
                    .andExpect(jsonPath("$.state").value("in_transit"))
                    .andExpect(jsonPath("$.amountCents").value(10_000))
                    .andExpect(jsonPath("$.feeCents").value(100))
                    .andExpect(jsonPath("$.netCents").value(9_900))
                    .andExpect(jsonPath("$.destination").value("TD ··3391"));
            assertThat(fx.balance(shop.merchantId())).isEqualTo(82_264 - 10_000);
            assertThat(events.stream(PayoutSent.class))
                    .anyMatch(e -> e.merchantId().equals(shop.merchantId()));
            mvc.perform(get("/api/v1/merchants/{id}/payouts", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].itemCount").value(1));
        }

        @Test
        void minimumFeeIsFiftyCents() throws Exception {
            mvc.perform(instant("{\"amountCents\":1000}", Ids.next(), "dev"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.feeCents").value(50));
        }

        @Test
        void sameKeyReplaysTheFirstResponse_withoutPayingTwice() throws Exception {
            var key = Ids.next();
            var first = mvc.perform(instant("{\"amountCents\":5000}", key, "dev"))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            mvc.perform(instant("{\"amountCents\":5000}", key, null))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));
            assertThat(fx.balance(shop.merchantId())).isEqualTo(82_264 - 5_000);
        }

        @Test
        void sameKeyDifferentBody_isRefused() throws Exception {
            var key = Ids.next();
            mvc.perform(instant("{\"amountCents\":5000}", key, "dev")).andExpect(status().isCreated());
            mvc.perform(instant("{\"amountCents\":6000}", key, "dev"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        }

        @Test
        void missingIdempotencyKey_is422() throws Exception {
            mvc.perform(instant("{\"amountCents\":5000}", null, "dev"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                    .andExpect(jsonPath("$.errors[0].message").value("Idempotency-Key header is required."));
        }

        @Test
        void withoutFreshStepUp_isForbidden_andTheKeyCanBeRetried() throws Exception {
            var key = Ids.next();
            mvc.perform(instant("{\"amountCents\":5000}", key, null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            mvc.perform(instant("{\"amountCents\":5000}", key, "not-a-proof"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            mvc.perform(instant("{\"amountCents\":5000}", key, "dev")).andExpect(status().isCreated());
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "{}|Enter an amount.",
                    "{\"amountCents\":50}|Instant payouts start at $1.00.",
                    "{\"amountCents\":82265}|You can pay out up to $822.64."
                })
        void amountRules(String body, String message) throws Exception {
            mvc.perform(instant(body, Ids.next(), "dev"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("amountCents"))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void onlyOwnersMoveMoney() throws Exception {
            var bookkeeper = fx.member(shop, MerchantRole.BOOKKEEPER);
            mvc.perform(post("/api/v1/merchants/{id}/payouts/instant", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountCents\":5000}")
                            .header("Idempotency-Key", Ids.next())
                            .header("X-Step-Up", "dev")
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/merchants/{id}/payouts", shop.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
        }

        @Test
        void nonMemberIsForbidden() throws Exception {
            mvc.perform(get("/api/v1/merchants/{id}/payouts/overview", shop.merchantId())
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
        }
    }

    @Nested
    class Schedule {

        @Test
        void previewThenSave() throws Exception {
            mvc.perform(get("/api/v1/merchants/{id}/payouts/schedule/preview", shop.merchantId())
                            .param("frequency", "weekly")
                            .param("weekday", "1")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amountCents").value(82_264))
                    .andExpect(jsonPath("$.nextPayoutAt").exists());
            mvc.perform(get("/api/v1/merchants/{id}/payouts/schedule/preview", shop.merchantId())
                            .param("frequency", "manual")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.nextPayoutAt").doesNotExist());
            mvc.perform(put("/api/v1/merchants/{id}/payouts/schedule", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"frequency\":\"monthly\",\"monthlyAnchor\":\"fifteenth\",\"reserve\":\"keep_500\"}")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schedule.frequency").value("monthly"))
                    .andExpect(jsonPath("$.schedule.monthlyAnchor").value("fifteenth"))
                    .andExpect(jsonPath("$.schedule.reserve").value("keep_500"))
                    .andExpect(jsonPath("$.reserveCents").value(50_000))
                    .andExpect(jsonPath("$.payableCents").value(32_264));
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"reserve\":\"none\"}|frequency|Choose a payout schedule.",
                    "{\"frequency\":\"weekly\"}|reserve|Choose a reserve option.",
                    "{\"frequency\":\"weekly\",\"reserve\":\"none\"}|weekday|Choose a day of the week.",
                    "{\"frequency\":\"weekly\",\"weekday\":6,\"reserve\":\"none\"}|weekday|Choose a day of the week.",
                    "{\"frequency\":\"monthly\",\"reserve\":\"none\"}|monthlyAnchor|Choose a day of the month."
                })
        void scheduleRules(String body, String field, String message) throws Exception {
            mvc.perform(put("/api/v1/merchants/{id}/payouts/schedule", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void bookkeeperCannotChangeIt() throws Exception {
            mvc.perform(put("/api/v1/merchants/{id}/payouts/schedule", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"frequency\":\"daily\",\"reserve\":\"none\"}")
                            .with(TestJwt.member(fx.member(shop, MerchantRole.BOOKKEEPER))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class BankAccount {

        private String prepareManual() throws Exception {
            var body = mvc.perform(post("/api/v1/merchants/{id}/payouts/bank-accounts", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"method":"manual","institution":"003","transit":"12345","accountNumber":"0012348820",
                                     "holderName":"Prairie Wrench Automotive Ltd."}""")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.label").value("RBC ··8820"))
                    .andExpect(jsonPath("$.state").value("draft"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            return JsonPath.read(body, "$.id");
        }

        private MockHttpServletRequestBuilder confirm(String accountId, String proof) {
            var req = post("/api/v1/merchants/{id}/payouts/bank-accounts/{a}/confirm", shop.merchantId(), accountId)
                    .header("Idempotency-Key", Ids.next())
                    .with(TestJwt.member(shop.ownerId()));
            return proof == null ? req : req.header("X-Step-Up", proof);
        }

        @Test
        void manualAccount_confirmedWithPasskey_pausesPayouts24h_thenTakesOver() throws Exception {
            var id = prepareManual();
            mvc.perform(confirm(id, null)).andExpect(status().isForbidden());
            mvc.perform(confirm(id, "dev"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("pending"))
                    .andExpect(jsonPath("$.effectiveAt").exists());
            assertThat(events.stream(PayoutAccountChanged.class))
                    .anyMatch(
                            e -> e.phase().equals("requested") && e.merchantId().equals(shop.merchantId()));
            mvc.perform(get("/api/v1/merchants/{id}/payouts/overview", shop.merchantId())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.pausedUntil").exists())
                    .andExpect(jsonPath("$.pendingAccount.label").value("RBC ··8820"))
                    .andExpect(jsonPath("$.account.label").value("TD ··3391"));
            mvc.perform(instant("{\"amountCents\":5000}", Ids.next(), "dev"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("payouts_paused"));

            jdbc.sql("update payments.payout_accounts set effective_at = now() - interval '1 minute' where id = ?")
                    .params(id)
                    .update();
            assertThat(jobs.activatePayoutAccounts()).isGreaterThanOrEqualTo(1);
            assertThat(events.stream(PayoutAccountChanged.class))
                    .anyMatch(e -> e.phase().equals("effective"));
            mvc.perform(get("/api/v1/merchants/{id}/payouts/overview", shop.merchantId())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.account.label").value("RBC ··8820"))
                    .andExpect(jsonPath("$.pausedUntil").doesNotExist());
        }

        @Test
        void instantLink_usesTheFinancialConnectionsAccount() throws Exception {
            mvc.perform(post("/api/v1/merchants/{id}/payouts/bank-accounts/link-session", shop.merchantId())
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("fake"));
            mvc.perform(post("/api/v1/merchants/{id}/payouts/bank-accounts", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"method\":\"instant\",\"linkedAccount\":\"btok_local_003_8820\"}")
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.method").value("instant"))
                    .andExpect(jsonPath("$.label").value("RBC ··8820"))
                    .andExpect(jsonPath("$.holderName").value("Prairie Wrench Automotive Ltd."));
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"institution\":\"003\"}|method|Choose how to add the account.",
                    "{\"method\":\"instant\"}|linkedAccount|Connect your bank first.",
                    "{\"method\":\"manual\",\"institution\":\"03\",\"transit\":\"12345\",\"accountNumber\":\"1234567\",\"holderName\":\"X\"}|institution|Enter the 3-digit institution number.",
                    "{\"method\":\"manual\",\"institution\":\"003\",\"transit\":\"1234\",\"accountNumber\":\"1234567\",\"holderName\":\"X\"}|transit|Enter the 5-digit transit number.",
                    "{\"method\":\"manual\",\"institution\":\"003\",\"transit\":\"12345\",\"accountNumber\":\"123456\",\"holderName\":\"X\"}|accountNumber|Account numbers are 7 to 12 digits.",
                    "{\"method\":\"manual\",\"institution\":\"003\",\"transit\":\"12345\",\"accountNumber\":\"1234567\"}|holderName|Enter the account holder's legal name.",
                    "{\"method\":\"manual\",\"transit\":\"12345\",\"accountNumber\":\"1234567\",\"holderName\":\"X\"}|institution|Enter the 3-digit institution number."
                })
        void bankRules(String body, String field, String message) throws Exception {
            mvc.perform(post("/api/v1/merchants/{id}/payouts/bank-accounts", shop.merchantId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].message")
                            .value(message));
        }

        @Test
        void accountNumberIsNeverStored() throws Exception {
            prepareManual();
            var stored = jdbc.sql("select count(*) from payments.payout_accounts where external_ref like '%0012348820%'"
                            + " or holder_name like '%0012348820%'")
                    .query(Long.class)
                    .single();
            assertThat(stored).isZero();
        }
    }
}
