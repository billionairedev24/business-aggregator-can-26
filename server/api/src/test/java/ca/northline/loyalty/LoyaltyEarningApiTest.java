package ca.northline.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.loyalty.application.LoyaltyUseCases.LoyaltyJobs;
import ca.northline.payments.PaymentsFixture;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-152 earning: a completed sale earns when its escrow is released — by the region's rule (2 points per dollar by
 * default), double for Plus members — a running provider-funded reward adds its extra points from its budget and
 * charges the business, refunds take their share back, points expire by the rule, and the account shows it all.
 */
@Import(PaymentsFixture.class)
class LoyaltyEarningApiTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    CustomerCases cases;

    @Autowired
    DisputeDecisions decisions;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    LoyaltyJobs loyaltyJobs;

    @Autowired
    JdbcClient jdbc;

    record Held(String escrowId, String refId) {}

    Held hold(String merchantId, String customer, long amount, @Nullable String listingId, Instant at) {
        var refId = Ids.next();
        var id = escrows.hold(new EscrowLifecycle.Hold(
                merchantId,
                EscrowKind.SERVICE,
                "booking",
                refId,
                amount,
                Math.round(amount * 0.05),
                customer,
                "D. Kowalski",
                "Brake inspection",
                null,
                listingId,
                "Brake inspection",
                "search",
                "pi_" + Ids.next(),
                at,
                null,
                null));
        return new Held(id, refId);
    }

    Held completed(String merchantId, String customer, long amount) {
        var h = hold(merchantId, customer, amount, null, Instant.now());
        escrows.confirmed("booking", h.refId(), Instant.now());
        return h;
    }

    long points(String user) {
        return jdbc.sql("select coalesce(sum(delta), 0) from trust.points_ledger where user_id = ?")
                .params(user)
                .query(Long.class)
                .single();
    }

    Map<String, Long> byType(String user) {
        return jdbc
                .sql("select ref_type, sum(delta) as d from trust.points_ledger where user_id = ? group by ref_type")
                .params(user)
                .query((rs, _) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    void reward(PaymentsFixture.Shop shop, int multiplier, long budgetCents) throws Exception {
        mvc.perform(put("/api/v1/merchants/{m}/reward", shop.merchantId())
                        .with(TestJwt.member(shop.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":true,"multiplier":%d,"label":"brake jobs","endsOn":"%s","budgetCents":%d}""".formatted(multiplier, LocalDate.now().plusDays(10), budgetCents)))
                .andExpect(status().isOk());
    }

    long ledger(String account, String side, String refType) {
        return jdbc.sql("select coalesce(sum(" + side + "_cents), 0) from payments.ledger_entries where account = ?"
                        + " and ref_type = ?")
                .params(account, refType)
                .query(Long.class)
                .single();
    }

    @Test
    void aCompletedBookingEarnsTwoPointsPerDollar_onlyOnceItsMoneyIsReleased() throws Exception {
        var shop = fx.shop("provider", "master");
        var customer = data.user("Dana Kowalski");
        var h = hold(shop.merchantId(), customer, 12_345, null, Instant.now());
        escrows.fulfilled("booking", h.refId(), Instant.now()); // completed: the 48 h window starts
        Thread.sleep(300);
        assertThat(points(customer)).as("nothing before the release").isZero();

        escrows.confirmed("booking", h.refId(), Instant.now()); // the customer's sign-off releases
        await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 246); // $123 × 2

        mvc.perform(get("/api/v1/me/points").with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(246))
                .andExpect(jsonPath("$.valueCents").value(246))
                .andExpect(jsonPath("$.pointsPerDollarSpent").value(2))
                .andExpect(jsonPath("$.redeemPointsPerDollar").value(100))
                .andExpect(jsonPath("$.expiryMonths").value(18))
                .andExpect(jsonPath("$.plusMultiplierPct").value(100))
                .andExpect(jsonPath("$.activity[0].type").value("earn"))
                .andExpect(jsonPath("$.activity[0].points").value(246))
                .andExpect(jsonPath("$.activity[0].note").value("Brake inspection"))
                .andExpect(jsonPath("$.activity[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.nextExpiryPoints").value(246));
        var expires = jdbc.sql("select expires_at from loyalty.earnings where escrow_id = ?")
                .params(h.escrowId())
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
        assertThat(expires)
                .isBetween(
                        Instant.now().plus(540, ChronoUnit.DAYS), Instant.now().plus(560, ChronoUnit.DAYS));

        // a replayed release (the listener retried) earns nothing more
        escrows.confirmed("booking", h.refId(), Instant.now());
        Thread.sleep(300);
        assertThat(points(customer)).isEqualTo(246);
    }

    @Test
    void aPlusMemberEarnsDouble() throws Exception {
        var shop = fx.shop("provider", "master");
        var customer = data.user("Plus Member");
        data.card(customer, "pm_card_visa_" + customer, true); // S-162: Plus renews on a saved card
        mvc.perform(post("/api/v1/me/plus")
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", Ids.next())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"monthly\"}"))
                .andExpect(status().isCreated());
        completed(shop.merchantId(), customer, 5_000);
        await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 200); // $50 × 2 × 2
        mvc.perform(get("/api/v1/me/points").with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.plusMultiplierPct").value(200));
    }

    @Test
    void aSaleOfABusinessWithoutPoints_whereTheRuleEarnsNothing() throws Exception {
        var shop = fx.shop("provider", "master");
        jdbc.sql("update merchants.merchants set province = 'QC', city = 'Earnville' where id = ?")
                .params(shop.merchantId())
                .update();
        try {
            jdbc.sql("""
                            insert into region.loyalty_rules (id, region_id, earn_points_per_dollar, earn_kinds,
                                   expiry_months, referral_points)
                            values ('test-qc', 'prov-qc', 5, '{goods,food}', null, 0)
                            """).update();
            var customer = data.user("Rule Tester");
            completed(shop.merchantId(), customer, 10_000);
            Thread.sleep(500);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> jdbc.sql("select count(*) from loyalty.earnings where customer_id = ?")
                                    .params(customer)
                                    .query(Long.class)
                                    .single()
                            == 1);
            assertThat(points(customer))
                    .as("services don't earn under this rule")
                    .isZero();
            assertThat(jdbc.sql("select rule_id from loyalty.earnings where customer_id = ?")
                            .params(customer)
                            .query(String.class)
                            .single())
                    .isEqualTo("test-qc");

            jdbc.sql("update region.loyalty_rules set earn_kinds = '{goods,food,service}' where id = 'test-qc'")
                    .update();
            var another = data.user("Rule Tester 2");
            completed(shop.merchantId(), another, 10_000);
            await().atMost(Duration.ofSeconds(10)).until(() -> points(another) == 500); // 5 a dollar
            assertThat(jdbc.sql("select expires_at is null from loyalty.earnings where customer_id = ?")
                            .params(another)
                            .query(Boolean.class)
                            .single())
                    .as("this rule's points never expire")
                    .isTrue();
        } finally {
            jdbc.sql("delete from region.loyalty_rules where id = 'test-qc'").update();
        }
    }

    @Nested
    class ProviderFundedRewards {

        @Test
        void theExtraPointsComeFromTheBudget_andTheBusinessIsCharged() throws Exception {
            var shop = fx.shop("provider", "master");
            reward(shop, 3, 5_000);
            var customer = data.user("Reward Customer");
            var h = completed(shop.merchantId(), customer, 10_000);
            // 200 points + 2 × 200 extra; the extra 400 cost the business $4.00 (a point is $0.01)
            await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 600);
            assertThat(byType(customer)).containsEntry("earn", 200L).containsEntry("reward", 400L);
            assertThat(jdbc.sql(
                                    "select funded_by_merchant_id from trust.points_ledger where user_id = ? and ref_type = 'reward'")
                            .params(customer)
                            .query(String.class)
                            .single())
                    .isEqualTo(shop.merchantId());
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> jdbc.sql("select count(*) from payments.reward_charges where escrow_id = ?")
                                    .params(h.escrowId())
                                    .query(Long.class)
                                    .single()
                            == 1);
            assertThat(jdbc.sql("select cents from payments.reward_charges where escrow_id = ?")
                            .params(h.escrowId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(400);
            // ledger: from the business's balance to rewards_funded; out of the sale's transfer before any payout
            var merchantDebit = jdbc.sql("""
                            select coalesce(sum(debit_cents), 0) from payments.ledger_entries
                             where account = ? and ref_type = 'reward_charge'""")
                    .params("merchant:" + shop.merchantId())
                    .query(Long.class)
                    .single();
            assertThat(merchantDebit).isEqualTo(400);
            assertThat(jdbc.sql("select reversed_cents from payments.transfers where escrow_id = ?")
                            .params(h.escrowId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(400);

            mvc.perform(get("/api/v1/merchants/{m}/reward", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.budgetCents").value(5_000))
                    .andExpect(jsonPath("$.spentCents").value(400))
                    .andExpect(jsonPath("$.pointsCredited").value(400))
                    .andExpect(jsonPath("$.salesCredited").value(1))
                    .andExpect(jsonPath("$.exhausted").value(false))
                    .andExpect(jsonPath("$.running").value(true));
        }

        @Test
        void itStopsAtItsBudget() throws Exception {
            var shop = fx.shop("provider", "master");
            reward(shop, 3, 1_000); // $10: two full sales ($4 each) and $2 of a third
            for (var i = 0; i < 3; i++) {
                var customer = data.user("Budget " + i);
                completed(shop.merchantId(), customer, 10_000);
                final var expected = i < 2 ? 600L : 400L;
                await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == expected);
            }
            var late = data.user("Budget late");
            completed(shop.merchantId(), late, 10_000);
            await().atMost(Duration.ofSeconds(10)).until(() -> points(late) == 200); // no extra any more
            mvc.perform(get("/api/v1/merchants/{m}/reward", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                    .andExpect(jsonPath("$.spentCents").value(1_000))
                    .andExpect(jsonPath("$.exhausted").value(true))
                    .andExpect(jsonPath("$.running").value(false));
            mvc.perform(get("/api/v1/public/merchants/{m}/reward", shop.merchantId()))
                    .andExpect(status().isNoContent());
        }

        @Test
        void aSaleFromBeforeTheRewardStarted_getsNoExtra() throws Exception {
            var shop = fx.shop("provider", "master");
            var customer = data.user("Early bird");
            var h = hold(
                    shop.merchantId(), customer, 10_000, null, Instant.now().minus(Duration.ofDays(2)));
            reward(shop, 2, 5_000);
            escrows.confirmed("booking", h.refId(), Instant.now());
            await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 200);
            Thread.sleep(300);
            assertThat(byType(customer)).doesNotContainKey("reward");
        }

        @Test
        void aRewardOnSomeListings_paysOnlyOnThem() throws Exception {
            var shop = fx.shop("provider", "master");
            mvc.perform(put("/api/v1/merchants/{m}/reward", shop.merchantId())
                            .with(TestJwt.member(shop.ownerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"active":true,"multiplier":2,"endsOn":"%s","budgetCents":5000,
                                     "listingIds":["svc-brakes"]}""".formatted(LocalDate.now().plusDays(5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.listingIds[0]").value("svc-brakes"));
            var onIt = data.user("Brakes");
            var h1 = hold(shop.merchantId(), onIt, 10_000, "svc-brakes", Instant.now());
            escrows.confirmed("booking", h1.refId(), Instant.now());
            var other = data.user("Oil");
            var h2 = hold(shop.merchantId(), other, 10_000, "svc-oil", Instant.now());
            escrows.confirmed("booking", h2.refId(), Instant.now());
            await().atMost(Duration.ofSeconds(10)).until(() -> points(onIt) == 400 && points(other) == 200);
        }
    }

    @Test
    void aRefundAfterTheRelease_takesBackItsShare_ofThePointsAndTheRewardsCharge() throws Exception {
        var shop = fx.shop("provider", "master");
        reward(shop, 2, 5_000);
        var customer = data.user("Refunded");
        var h = completed(shop.merchantId(), customer, 10_000);
        await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 400); // 200 + 200 extra

        var refund = cases.requestReview(h.escrowId(), customer, 5_000, "Half the job");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue();

        await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 200);
        assertThat(byType(customer)).containsEntry("earn_reversal", -100L).containsEntry("reward_reversal", -100L);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select coalesce(sum(cents), 0) from payments.reward_charges where escrow_id = ?"
                                        + " and kind = 'refund'")
                                .params(h.escrowId())
                                .query(Long.class)
                                .single()
                        == 100);
        mvc.perform(get("/api/v1/merchants/{m}/reward", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                .andExpect(jsonPath("$.spentCents").value(100))
                .andExpect(jsonPath("$.pointsCredited").value(100));
    }

    @Test
    void aPartialRefundBeforeTheRelease_isLeftOutOfTheEarning_andTakesNothingBackAfterwards() throws Exception {
        var shop = fx.shop("provider", "master");
        var customer = data.user("Part refunded");
        var h = hold(shop.merchantId(), customer, 10_000, null, Instant.now());
        escrows.fulfilled("booking", h.refId(), Instant.now());
        var refund = cases.requestReview(h.escrowId(), customer, 4_000, "Part of the job");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue(); // pays the refund and releases the rest at once
        await().atMost(Duration.ofSeconds(10)).until(() -> points(customer) == 120); // the $60 kept × 2
        Thread.sleep(500);
        assertThat(points(customer)).isEqualTo(120);
        assertThat(byType(customer)).doesNotContainKey("earn_reversal");
    }

    @Test
    void aRefundInFullBeforeTheRelease_meansNothingIsEarned() throws Exception {
        var shop = fx.shop("provider", "master");
        var customer = data.user("Never earned");
        var h = hold(shop.merchantId(), customer, 8_000, null, Instant.now());
        var refund = cases.requestReview(h.escrowId(), customer, 8_000, "Cancelled");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue();
        Thread.sleep(500);
        assertThat(points(customer)).isZero();
        assertThat(jdbc.sql("select count(*) from loyalty.earnings where customer_id = ?")
                        .params(customer)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theExpiryJob_recordsWhatEachExpiredCreditHadLeft_once() throws Exception {
        var customer = data.user("Expiring");
        jdbc.sql("""
                        insert into trust.points_ledger (id, user_id, delta, ref_type, ref_id, created_at, expires_at, note)
                        values (?, ?, 300, 'earn', ?, now() - interval '20 months', now() - interval '2 months', 'Old order'),
                               (?, ?, -100, 'redemption', ?, now() - interval '19 months', null, 'Checkout'),
                               (?, ?, 50, 'earn', ?, now() - interval '1 month', now() + interval '17 months', 'New order')
                        """)
                .params(
                        Ids.next(),
                        customer,
                        Ids.next(),
                        Ids.next(),
                        customer,
                        Ids.next(),
                        Ids.next(),
                        customer,
                        Ids.next())
                .update();
        mvc.perform(get("/api/v1/me/points").with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.balance").value(50)); // the 200 left of the old credit already don't count
        loyaltyJobs.expirePoints();
        loyaltyJobs.expirePoints();
        assertThat(byType(customer)).containsEntry("expiry", -200L);
        assertThat(points(customer)).isEqualTo(50);
        mvc.perform(get("/api/v1/me/points").with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.balance").value(50))
                .andExpect(jsonPath("$.activity[0].type").value("expiry"))
                .andExpect(jsonPath("$.activity[0].points").value(-200));
    }

    @Test
    void pointsNeedASignIn() throws Exception {
        mvc.perform(get("/api/v1/me/points")).andExpect(status().isUnauthorized());
    }
}
