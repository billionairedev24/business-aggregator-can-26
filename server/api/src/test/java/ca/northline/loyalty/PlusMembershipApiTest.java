package ca.northline.loyalty;

import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.loyalty.api.PlusBenefits;
import ca.northline.payments.api.PlusPaymentUpdated;
import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.payments.infra.FakeStripeBank;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-152 Northline Plus, billed by Northline itself since S-162 (no Stripe Billing): joining needs a saved card and
 * starts the household's 30-day trial, or — a household that had Plus — charges the first period now (tax, ledger, a
 * receipt); the payments job charges due renewals off-session exactly once (concurrent runs too), retries a refused one
 * on the retry schedule (1, 3, 5 days; benefits continue) and then lapses it; the bank's check
 * ({@code authentication_required}) asks the member to confirm; cancelling stops the next renewal; a plan change waits
 * for it; another card recovers a payment being retried; finance refunds a period; Plus's PaymentIntent webhook. The
 * benefits (member prices in the cart) as before. The fake gateway decides by the card's id (see
 * {@code TestData.card}); nothing here reaches Stripe.
 */
@RecordApplicationEvents
class PlusMembershipApiTest extends IntegrationTest {

    static final String PLATFORM_SECRET = "whsec_test_platform_fake";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentsJobs paymentsJobs;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    PlusBenefits benefits;

    @Autowired
    ApplicationEvents events;

    ResultActions join(String user, String body) throws Exception {
        return mvc.perform(post("/api/v1/me/plus")
                .with(TestJwt.customer(user))
                .header("Idempotency-Key", Ids.next())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** A person with a card that pays. */
    String customer(String name) {
        var user = data.user(name);
        data.card(user, "pm_card_visa_" + user, true);
        return user;
    }

    String household(String user) {
        return jdbc.sql("select household_id from identity.household_members where user_id = ?")
                .params(user)
                .query(String.class)
                .single();
    }

    String schedule(String user) {
        return jdbc.sql("select id from payments.plus_schedules where payer_id = ? order by created_at desc limit 1")
                .params(user)
                .query(String.class)
                .single();
    }

    String scheduleStatus(String user) {
        return jdbc.sql("select status from payments.plus_schedules where id = ?")
                .params(schedule(user))
                .query(String.class)
                .single();
    }

    String membershipStatus(String user) {
        return jdbc.sql("select status from loyalty.memberships where payer_id = ?")
                .params(user)
                .query(String.class)
                .single();
    }

    /** The open period (state, attempts, next try, amount, tax, payment intent). */
    record Open(String id, String state, int attempts, Instant nextAttemptAt, Instant startsAt, long amount, long tax,
            String paymentIntent, String plan) {}

    Open open(String user) {
        return jdbc.sql("""
                        select id, state, attempts, next_attempt_at, starts_at, amount_cents, tax_cents, payment_intent, plan
                          from payments.plus_periods
                         where schedule_id = ? and state in ('scheduled', 'retrying', 'requires_action')""")
                .params(schedule(user))
                .query((rs, _) -> new Open(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant(),
                        rs.getObject(5, OffsetDateTime.class).toInstant(),
                        rs.getLong(6),
                        rs.getLong(7),
                        rs.getString(8),
                        rs.getString(9)))
                .single();
    }

    long paidPeriods(String user) {
        return jdbc.sql("select count(*) from payments.plus_periods where schedule_id = ? and state = 'paid'")
                .params(schedule(user))
                .query(Long.class)
                .single();
    }

    /** The open period is due now (the renewal job's next run charges it). */
    void due(String user) {
        jdbc.sql("""
                        update payments.plus_periods set next_attempt_at = now() - interval '1 minute'
                         where schedule_id = ? and state in ('scheduled', 'retrying', 'requires_action')""")
                .params(schedule(user))
                .update();
    }

    /** Joins (the trial), then the trial ends and its first period is charged: an active, paying member. */
    String member(String name) throws Exception {
        var user = customer(name);
        join(user, "{\"plan\":\"monthly\"}").andExpect(status().isCreated());
        due(user);
        paymentsJobs.chargePlusRenewals();
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("active"));
        return user;
    }

    long ledger(String account, String periodId, boolean credit) {
        return jdbc.sql("select coalesce(sum(" + (credit ? "credit_cents" : "debit_cents") + "), 0)"
                        + " from payments.ledger_entries where account = ? and ref_type = 'plus_invoice' and ref_id = ?")
                .params(account, periodId)
                .query(Long.class)
                .single();
    }

    long notices(String user, String outcome) {
        return events.stream(PlusPaymentUpdated.class)
                .filter(e -> e.customerId().equals(user) && e.outcome().equals(outcome))
                .count();
    }

    @Test
    void withoutPlus_thePricesTheTrialAndTheBenefitsOnOffer() throws Exception {
        var user = data.user("Curious");
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.monthlyPriceCents").value(799))
                .andExpect(jsonPath("$.annualPriceCents").value(6900))
                .andExpect(jsonPath("$.trialDays").value(30))
                .andExpect(jsonPath("$.trialAvailable").value(true))
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.benefits.member").value(false))
                .andExpect(jsonPath("$.benefits.serviceFeeWaived").value(true))
                .andExpect(jsonPath("$.benefits.pointsMultiplierPct").value(200));
        assertThat(benefits.of(user).member()).isFalse();
    }

    @Test
    void joiningNeedsASavedCard() throws Exception {
        var user = data.user("Cardless");
        join(user, "{\"plan\":\"monthly\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("card_required"))
                .andExpect(jsonPath("$.detail").value("Add a card in Payment methods first: Plus renews on it."));
    }

    @Test
    void joining_startsTheTrial_forTheWholeHousehold_andSchedulesTheFirstCharge() throws Exception {
        var user = customer("Joiner");
        join(user, "{\"plan\":\"monthly\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("trialing"))
                .andExpect(jsonPath("$.plan").value("monthly"))
                .andExpect(jsonPath("$.payer").value(true))
                .andExpect(jsonPath("$.benefits.member").value(true))
                .andExpect(jsonPath("$.trialAvailable").value(false))
                .andExpect(jsonPath("$.nextChargeCents").value(799))
                .andExpect(jsonPath("$.card.last4").value("4242"))
                .andExpect(jsonPath("$.history[0].state").value("scheduled"))
                .andExpect(jsonPath("$.history[1].state").value("free"));
        var first = open(user);
        assertThat(first.state()).isEqualTo("scheduled");
        assertThat(first.amount()).isEqualTo(799);
        assertThat(first.startsAt())
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(jdbc.sql("select rrule from payments.plus_schedules where id = ?")
                        .params(schedule(user))
                        .query(String.class)
                        .single())
                .startsWith("FREQ=MONTHLY;INTERVAL=1");
        assertThat(first.paymentIntent()).as("nothing charged during the trial").isNull();
        mvc.perform(get("/api/v1/me/household").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.plan").value("monthly"));

        var partner = data.user("Partner");
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (?, ?, 'member')")
                .params(household(user), partner)
                .update();
        assertThat(benefits.of(partner).member()).isTrue();
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(partner)))
                .andExpect(jsonPath("$.status").value("trialing"))
                .andExpect(jsonPath("$.payer").value(false))
                .andExpect(jsonPath("$.card").doesNotExist());
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(partner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_not_payer"));
        mvc.perform(put("/api/v1/me/plus/card")
                        .with(TestJwt.customer(partner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodId\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_not_payer"));

        join(user, "{\"plan\":\"annual\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_active"));
    }

    @Test
    void joiningNeedsAPlanAndAnIdempotencyKey_andAReplayAnswersTheSame() throws Exception {
        var user = customer("Careful");
        join(user, "{\"plan\":\"weekly\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("plan"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose monthly or annual."));
        mvc.perform(post("/api/v1/me/plus")
                        .with(TestJwt.customer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"monthly\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Idempotency-Key header is required."));
        var key = Ids.next();
        for (var i = 0; i < 2; i++) {
            var result = mvc.perform(post("/api/v1/me/plus")
                            .with(TestJwt.customer(user))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"plan\":\"monthly\"}"))
                    .andExpect(status().isCreated());
            if (i == 1) {
                result.andExpect(header().string("Idempotent-Replayed", "true"));
            }
        }
        assertThat(jdbc.sql("select count(*) from payments.plus_schedules where payer_id = ?")
                        .params(user)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theTrialsEnd_isChargedWithTax_toTheLedger_withAReceipt() throws Exception {
        var user = member("First charge");
        var paid = jdbc.sql("""
                        select id, amount_cents, tax_cents, payment_intent, tax_transaction, province
                          from payments.plus_periods where schedule_id = ? and state = 'paid'""")
                .params(schedule(user))
                .query((rs, _) -> new Object[] {
                    rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)
                })
                .single();
        var periodId = (String) paid[0];
        assertThat(paid[1]).isEqualTo(799L);
        assertThat(paid[2]).as("GST on $7.99 in the default province").isEqualTo(40L);
        assertThat((String) paid[3]).startsWith("pi_");
        assertThat(paid[4]).as("reported to the tax gateway").isNotNull();
        assertThat(paid[5]).isEqualTo("AB");
        assertThat(ledger("stripe_balance", periodId, false)).isEqualTo(839);
        assertThat(ledger("revenue", periodId, true)).isEqualTo(799);
        assertThat(ledger("tax_payable", periodId, true)).isEqualTo(40);
        assertThat(notices(user, "paid")).isEqualTo(1);
        assertThat(open(user).state()).as("the next period waits for its date").isEqualTo("scheduled");
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.history[1].state").value("paid"))
                .andExpect(jsonPath("$.history[1].taxCents").value(40))
                .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    void aSecondMembership_hasNoTrial_andIsChargedAtTheJoin() throws Exception {
        var user = member("Returning");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user))).andExpect(status().isOk());
        due(user);
        paymentsJobs.chargePlusRenewals();
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        assertThat(benefits.of(user).member()).isFalse();

        join(user, "{\"plan\":\"annual\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.plan").value("annual"));
        assertThat(paidPeriods(user)).isEqualTo(1);
        assertThat(jdbc.sql("select amount_cents from payments.plus_periods where schedule_id = ? and state = 'paid'")
                        .params(schedule(user))
                        .query(Long.class)
                        .single())
                .isEqualTo(6900);
        var next = open(user);
        assertThat(next.startsAt())
                .isBetween(Instant.now().plus(Duration.ofDays(364)), Instant.now().plus(Duration.ofDays(367)));
    }

    @Test
    void aDeclinedCardAtTheJoin_is409_andNothingIsKept() throws Exception {
        var user = member("Declined join");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user))).andExpect(status().isOk());
        due(user);
        paymentsJobs.chargePlusRenewals();
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        var schedules = jdbc.sql("select count(*) from payments.plus_schedules where payer_id = ?")
                .params(user)
                .query(Long.class)
                .single();
        data.card(user, "pm_card_chargeDeclined_" + user, true);
        join(user, "{\"plan\":\"monthly\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("card_declined"))
                .andExpect(jsonPath("$.detail").value("Your bank declined the card. Try another card in Payment methods."));
        assertThat(jdbc.sql("select count(*) from payments.plus_schedules where payer_id = ?")
                        .params(user)
                        .query(Long.class)
                        .single())
                .isEqualTo(schedules);
    }

    @Test
    void aDueRenewal_isChargedOnce_evenWhenJobRunsOverlap() throws Exception {
        var user = member("Overlap");
        var renewal = open(user);
        due(user);
        var barrier = new CyclicBarrier(4);
        var tasks = new ArrayList<Callable<Integer>>();
        for (var i = 0; i < 4; i++) {
            tasks.add(() -> {
                barrier.await();
                return paymentsJobs.chargePlusRenewals();
            });
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : pool.invokeAll(tasks)) {
                f.get();
            }
        }
        assertThat(jdbc.sql("select state from payments.plus_periods where id = ?")
                        .params(renewal.id())
                        .query(String.class)
                        .single())
                .isEqualTo("paid");
        assertThat(paidPeriods(user)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from payments.plus_attempts where period_id = ?")
                        .params(renewal.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(ledger("revenue", renewal.id(), true)).as("booked once").isEqualTo(799);
        assertThat(open(user).state()).isEqualTo("scheduled");
        // a run with nothing due changes nothing
        assertThat(paymentsJobs.chargePlusRenewals()).isZero();
    }

    @Test
    void aRefusedRenewal_isRetriedOnDays1_3_5_withTheBenefits_thenLapses() throws Exception {
        var user = member("Retried");
        data.card(user, "pm_card_chargeDeclined_" + user, true);
        var renewal = open(user);
        var days = new int[] {1, 3, 5};
        for (var i = 0; i < 3; i++) {
            due(user);
            paymentsJobs.chargePlusRenewals();
            var now = open(user);
            assertThat(now.id()).isEqualTo(renewal.id());
            assertThat(now.state()).isEqualTo("retrying");
            assertThat(now.attempts()).isEqualTo(i + 1);
            assertThat(now.nextAttemptAt()).isEqualTo(renewal.startsAt().plus(Duration.ofDays(days[i])));
            await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("past_due"));
            assertThat(benefits.of(user).member()).as("the grace keeps the benefits").isTrue();
        }
        assertThat(notices(user, "declined")).isEqualTo(3);
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.status").value("past_due"))
                .andExpect(jsonPath("$.payment.reason").value("card_declined"))
                .andExpect(jsonPath("$.payment.totalCents").value(839))
                .andExpect(jsonPath("$.payment.clientSecret").doesNotExist());
        assertThat(jdbc.sql("select count(distinct payment_intent) from payments.plus_attempts where period_id = ?")
                        .params(renewal.id())
                        .query(Long.class)
                        .single())
                .as("one PaymentIntent per period, tried again")
                .isEqualTo(1);

        due(user);
        paymentsJobs.chargePlusRenewals();
        assertThat(jdbc.sql("select state from payments.plus_periods where id = ?")
                        .params(renewal.id())
                        .query(String.class)
                        .single())
                .isEqualTo("lapsed");
        assertThat(scheduleStatus(user)).isEqualTo("canceled");
        assertThat(notices(user, "lapsed")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        assertThat(benefits.of(user).member()).isFalse();
        assertThat(ledger("revenue", renewal.id(), true)).isZero();
    }

    @Test
    void anotherCard_recoversARenewalBeingRetried() throws Exception {
        var user = member("New card");
        var good = "pm_card_visa_" + user;
        data.card(user, "pm_card_insufficientFunds_" + user, true);
        due(user);
        paymentsJobs.chargePlusRenewals();
        assertThat(open(user).state()).isEqualTo("retrying");

        var stranger = customer("Stranger");
        mvc.perform(put("/api/v1/me/plus/card")
                        .with(TestJwt.customer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"pm_card_visa_%s\"}".formatted(stranger)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("paymentMethodId"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of your saved cards."));
        mvc.perform(put("/api/v1/me/plus/card")
                        .with(TestJwt.customer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"%s\"}".formatted(good)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.card.id").value(good))
                .andExpect(jsonPath("$.card.chosen").value(true));
        assertThat(open(user).nextAttemptAt()).as("tried on the next run").isBefore(Instant.now().plusSeconds(1));

        paymentsJobs.chargePlusRenewals();
        assertThat(paidPeriods(user)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("active"));
    }

    @Test
    void theBankWantsTheMember_whoConfirms_onTheWebOrInTheApp() throws Exception {
        var user = member("Three DS");
        data.card(user, "pm_card_authenticationRequired_" + user, true);
        due(user);
        paymentsJobs.chargePlusRenewals();
        var waiting = open(user);
        assertThat(waiting.state()).isEqualTo("requires_action");
        assertThat(notices(user, "action_required")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("past_due"));
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.payment.reason").value("authentication_required"))
                .andExpect(jsonPath("$.payment.periodId").value(waiting.id()))
                .andExpect(jsonPath("$.clientSecret").value(org.hamcrest.Matchers.startsWith(waiting.paymentIntent())));

        FakeStripeBank.authenticate(gateway, waiting.paymentIntent());
        mvc.perform(post("/api/v1/me/plus/confirm").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.payment").doesNotExist());
        assertThat(paidPeriods(user)).isEqualTo(2);
        mvc.perform(post("/api/v1/me/plus/confirm").with(TestJwt.customer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_nothing_to_confirm"));
    }

    @Test
    void aJoinThatNeedsTheBank_waits_andIsDroppedWhenNeverConfirmed() throws Exception {
        var user = member("Join 3DS");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user))).andExpect(status().isOk());
        due(user);
        paymentsJobs.chargePlusRenewals();
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        data.card(user, "pm_card_authenticationRequired_" + user, true);

        var body = join(user, "{\"plan\":\"monthly\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andExpect(jsonPath("$.clientSecret").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.clientSecret")).contains("_secret_");
        assertThat(benefits.of(user).member()).isFalse();
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andExpect(jsonPath("$.clientSecret").exists());

        due(user); // the confirmation window passed
        paymentsJobs.chargePlusRenewals();
        assertThat(scheduleStatus(user)).isEqualTo("canceled");
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        assertThat(jdbc.sql("select ended_reason from payments.plus_schedules where id = ?")
                        .params(schedule(user))
                        .query(String.class)
                        .single())
                .isEqualTo("unconfirmed");
    }

    @Test
    void aJoinConfirmedWithTheBank_starts() throws Exception {
        var user = member("Join confirmed");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user))).andExpect(status().isOk());
        due(user);
        paymentsJobs.chargePlusRenewals();
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        data.card(user, "pm_card_authenticationRequired_" + user, true);
        join(user, "{\"plan\":\"monthly\"}").andExpect(jsonPath("$.status").value("incomplete"));
        FakeStripeBank.authenticate(gateway, open(user).paymentIntent());
        mvc.perform(post("/api/v1/me/plus/confirm").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.benefits.member").value(true));
    }

    @Test
    void cancelling_stopsTheNextRenewal_andResumingUndoesIt() throws Exception {
        var user = member("Canceller");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.nextChargeAt").doesNotExist())
                .andExpect(jsonPath("$.benefits.member").value(true)); // until the end of what was paid for
        mvc.perform(post("/api/v1/me/plus/resume").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.nextChargeAt").exists());
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user))).andExpect(status().isOk());
        var renewal = open(user);
        due(user);
        paymentsJobs.chargePlusRenewals();
        assertThat(jdbc.sql("select state, payment_intent is null from payments.plus_periods where id = ?")
                        .params(renewal.id())
                        .query((rs, _) -> rs.getString(1) + "/" + rs.getBoolean(2))
                        .single())
                .isEqualTo("void/true");
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        var none = data.user("Nobody");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(none)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_none"));
    }

    @Test
    void aPlanChange_waitsForTheNextRenewal_andStartsTheNewRuleThere() throws Exception {
        var user = member("Switcher");
        mvc.perform(patch("/api/v1/me/plus")
                        .with(TestJwt.customer(user))
                        .header("Idempotency-Key", Ids.next())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"monthly\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That's already your plan."));
        mvc.perform(patch("/api/v1/me/plus")
                        .with(TestJwt.customer(user))
                        .header("Idempotency-Key", Ids.next())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"annual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("monthly"))
                .andExpect(jsonPath("$.nextPlan").value("annual"))
                .andExpect(jsonPath("$.nextChargeCents").value(6900));
        var renewal = open(user);
        due(user);
        paymentsJobs.chargePlusRenewals();
        var paid = jdbc.sql("select plan, amount_cents, ends_at from payments.plus_periods where id = ?")
                .params(renewal.id())
                .query((rs, _) -> new Object[] {
                    rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class).toInstant()
                })
                .single();
        assertThat(paid[0]).isEqualTo("annual");
        assertThat(paid[1]).isEqualTo(6900L);
        assertThat((Instant) paid[2]).isEqualTo(renewal.startsAt().atOffset(java.time.ZoneOffset.UTC).plusYears(1).toInstant());
        assertThat(open(user).plan()).isEqualTo("annual");
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("select plan from loyalty.memberships where payer_id = ?")
                .params(user)
                .query(String.class)
                .single()
                .equals("annual"));
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.plan").value("annual"))
                .andExpect(jsonPath("$.nextPlan").doesNotExist());
    }

    @Test
    void finance_refundsAPeriod_amountAndTax_andTheCurrentOneEndsTheMembership() throws Exception {
        var user = member("Refunded");
        var period = jdbc.sql("select id from payments.plus_periods where schedule_id = ? and state = 'paid'")
                .params(schedule(user))
                .query(String.class)
                .single();
        jdbc.sql("update payments.plus_periods set starts_at = now() - interval '1 day' where id = ?")
                .params(period)
                .update(); // the trial ended yesterday: this is the current period
        var support = data.user("Support agent");
        var finance = data.user("Finance lead");
        mvc.perform(post("/api/v1/console/plus/periods/{id}/refund", period).with(TestJwt.staff(support, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/console/plus/periods/{id}/refund", period).with(TestJwt.customer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/plus/households/{h}/periods", household(user))
                        .with(TestJwt.staff(finance, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].state".formatted(period)).value("paid"));
        mvc.perform(post("/api/v1/console/plus/periods/{id}/refund", period).with(TestJwt.staff(finance, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCents").value(839))
                .andExpect(jsonPath("$.ended").value(true))
                .andExpect(jsonPath("$.refundId").value(org.hamcrest.Matchers.startsWith("re_")));
        mvc.perform(post("/api/v1/console/plus/periods/{id}/refund", period).with(TestJwt.staff(finance, StaffRole.FINANCE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_not_refundable"));
        assertThat(ledger("revenue", period, true) - ledger("revenue", period, false)).isZero();
        assertThat(ledger("tax_payable", period, true) - ledger("tax_payable", period, false)).isZero();
        assertThat(ledger("stripe_balance", period, false) - ledger("stripe_balance", period, true)).isZero();
        assertThat(notices(user, "refunded")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("canceled"));
        assertThat(benefits.of(user).member()).isFalse();
    }

    @Test
    void aMembersCart_isPricedWithTheShopsMemberPrices() throws Exception {
        shopFixtures.categories();
        var shop = shopFixtures.shop("Cartville", "Plus Bakery", "master");
        var loaf = shopFixtures.listing(shop, BAKERY, "Members' loaf", 1_000, 10);
        jdbc.sql("update catalogue.offers set member_discount_pct = 15 where id = ?")
                .params(loaf.offerId())
                .update();
        var member = customer("Member shopper");
        join(member, "{\"plan\":\"monthly\"}").andExpect(status().isCreated());
        var other = data.user("Other shopper");
        for (var user : new String[] {member, other}) {
            mvc.perform(post("/api/v1/cart/items")
                            .with(TestJwt.customer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"offerId\":\"%s\",\"qty\":2}".formatted(loaf.offerId())))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/cart").with(TestJwt.customer(member)))
                .andExpect(jsonPath("$.groups[0].items[0].unitCents").value(850))
                .andExpect(jsonPath("$.groups[0].items[0].listUnitCents").value(1_000))
                .andExpect(jsonPath("$.groups[0].items[0].memberPrice").value(true))
                .andExpect(jsonPath("$.subtotalCents").value(1_700))
                .andExpect(jsonPath("$.plusSavingsCents").value(300));
        mvc.perform(get("/api/v1/cart").with(TestJwt.customer(other)))
                .andExpect(jsonPath("$.groups[0].items[0].unitCents").value(1_000))
                .andExpect(jsonPath("$.groups[0].items[0].memberUnitCents").value(850))
                .andExpect(jsonPath("$.groups[0].items[0].memberPrice").value(false))
                .andExpect(jsonPath("$.plusSavingsCents").value(0));
        mvc.perform(get("/api/v1/public/plus-prices").param("offers", loaf.offerId() + ",nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + loaf.offerId()).value(15));
    }

    // ── Plus PaymentIntent webhooks ──────────────────────────────────────────────────────────────────────────────

    @Nested
    class Webhooks {

        ResultActions deliver(String payload, String secret) throws Exception {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var t = Instant.now().getEpochSecond();
            var v1 = HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return mvc.perform(post("/api/v1/webhooks/stripe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Stripe-Signature", "t=" + t + ",v1=" + v1)
                    .content(payload));
        }

        String succeeded(String paymentIntent) {
            return """
                    {"id":"evt_%s","object":"event","api_version":"2026-08-26.dahlia","created":%d,"livemode":false,\
                    "type":"payment_intent.succeeded","pending_webhooks":1,"request":{"id":null,"idempotency_key":null},\
                    "data":{"object":{"id":"%s","object":"payment_intent","status":"succeeded","amount":839,\
                    "metadata":{"northline_kind":"plus"}}}}""".formatted(Ids.next(), Instant.now().getEpochSecond(), paymentIntent);
        }

        @Test
        void aPlusPaymentConfirmedAtTheBank_isSettledByItsWebhook_once() throws Exception {
            var user = member("Webhook");
            data.card(user, "pm_card_authenticationRequired_" + user, true);
            due(user);
            paymentsJobs.chargePlusRenewals();
            var waiting = open(user);
            FakeStripeBank.authenticate(gateway, waiting.paymentIntent());
            var payload = succeeded(waiting.paymentIntent());
            deliver(payload, "whsec_test_wrong_fake").andExpect(status().is4xxClientError());
            deliver(payload, PLATFORM_SECRET).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
            deliver(payload, PLATFORM_SECRET).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
            paymentsJobs.processStripeEvents();
            assertThat(jdbc.sql("select state from payments.plus_periods where id = ?")
                            .params(waiting.id())
                            .query(String.class)
                            .single())
                    .isEqualTo("paid");
            assertThat(ledger("revenue", waiting.id(), true)).isEqualTo(799);
            await().atMost(Duration.ofSeconds(10)).until(() -> membershipStatus(user).equals("active"));
        }
    }

    @Test
    void needsASignIn() throws Exception {
        mvc.perform(get("/api/v1/me/plus")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/plus/confirm")).andExpect(status().isUnauthorized());
    }
}
