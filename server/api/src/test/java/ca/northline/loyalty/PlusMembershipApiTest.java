package ca.northline.loyalty;

import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.loyalty.api.PlusBenefits;
import ca.northline.loyalty.application.LoyaltyUseCases.LoyaltyJobs;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-152 Northline Plus: joining (the local fake billing: a 30-day trial for a household's first membership), the
 * benefits (member prices in the cart; no food service fee and 2× points are covered with checkout and earning), plan
 * changes, cancelling at the period's end and resuming — payer only — the fake's renewals, and Stripe Billing's
 * webhooks: verified, de-duplicated, applied in order, invoices recorded once as revenue.
 */
class PlusMembershipApiTest extends IntegrationTest {

    static final String PLATFORM_SECRET = "whsec_test_platform_fake";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LoyaltyJobs loyaltyJobs;

    @Autowired
    PaymentsJobs paymentsJobs;

    @Autowired
    PlusBenefits benefits;

    ResultActions join(String user, String body) throws Exception {
        return mvc.perform(post("/api/v1/me/plus")
                .with(TestJwt.customer(user))
                .header("Idempotency-Key", Ids.next())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    String household(String user) {
        return jdbc.sql("select household_id from identity.household_members where user_id = ?")
                .params(user)
                .query(String.class)
                .single();
    }

    @Test
    void withoutPlus_thePricesTheTrialAndTheBenefitsOnOffer() throws Exception {
        var user = data.user("Curious");
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.monthlyPriceCents").value(999))
                .andExpect(jsonPath("$.annualPriceCents").value(9900))
                .andExpect(jsonPath("$.trialDays").value(30))
                .andExpect(jsonPath("$.trialAvailable").value(true))
                .andExpect(jsonPath("$.provider").value("local"))
                .andExpect(jsonPath("$.benefits.member").value(false))
                .andExpect(jsonPath("$.benefits.serviceFeeWaived").value(true))
                .andExpect(jsonPath("$.benefits.pointsMultiplierPct").value(200));
        assertThat(benefits.of(user).member()).isFalse();
    }

    @Test
    void joining_startsTheTrial_forTheWholeHousehold() throws Exception {
        var user = data.user("Joiner");
        join(user, "{\"plan\":\"monthly\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("trialing"))
                .andExpect(jsonPath("$.plan").value("monthly"))
                .andExpect(jsonPath("$.payer").value(true))
                .andExpect(jsonPath("$.benefits.member").value(true))
                .andExpect(jsonPath("$.trialAvailable").value(false));
        var end = jdbc.sql("select current_period_end from loyalty.memberships where payer_id = ?")
                .params(user)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
        assertThat(end)
                .isBetween(
                        Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        mvc.perform(get("/api/v1/me/household").with(TestJwt.customer(user)))
                .andExpect(jsonPath("$.plan").value("monthly"));

        var partner = data.user("Partner");
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (?, ?, 'member')")
                .params(household(user), partner)
                .update();
        assertThat(benefits.of(partner).member()).isTrue();
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(partner)))
                .andExpect(jsonPath("$.status").value("trialing"))
                .andExpect(jsonPath("$.payer").value(false));
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(partner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_not_payer"));

        join(user, "{\"plan\":\"annual\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_active"));
    }

    @Test
    void joiningNeedsAPlanAndAnIdempotencyKey_andAReplayAnswersTheSame() throws Exception {
        var user = data.user("Careful");
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
        assertThat(jdbc.sql("select count(*) from loyalty.memberships where payer_id = ?")
                        .params(user)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void thePayerSwitchesPlans_cancelsAtThePeriodsEnd_andResumes() throws Exception {
        var user = data.user("Switcher");
        join(user, "{\"plan\":\"monthly\"}").andExpect(status().isCreated());
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
                .andExpect(jsonPath("$.plan").value("annual"));
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true))
                .andExpect(jsonPath("$.benefits.member").value(true)); // until the end of what was paid for
        mvc.perform(post("/api/v1/me/plus/resume").with(TestJwt.customer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        var none = data.user("Nobody");
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(none)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("plus_none"));
    }

    @Test
    void theFakeRenews_withAPaidInvoice_orEndsACancelledMembership() throws Exception {
        var renewing = data.user("Renewing");
        join(renewing, "{\"plan\":\"monthly\"}").andExpect(status().isCreated());
        var ending = data.user("Ending");
        join(ending, "{\"plan\":\"monthly\"}").andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/me/plus").with(TestJwt.customer(ending))).andExpect(status().isOk());
        jdbc.sql(
                        "update loyalty.memberships set current_period_end = now() - interval '1 minute' where payer_id in (?, ?)")
                .params(renewing, ending)
                .update();

        loyaltyJobs.renewLocalMemberships();

        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(renewing)))
                .andExpect(jsonPath("$.status").value("active"));
        var household = household(renewing);
        assertThat(jdbc.sql("select amount_cents from payments.plus_invoices where household_id = ?")
                        .params(household)
                        .query(Long.class)
                        .single())
                .isEqualTo(999);
        assertThat(jdbc.sql("""
                        select coalesce(sum(credit_cents), 0) from payments.ledger_entries
                         where account = 'revenue' and ref_type = 'plus_invoice'
                           and ref_id = (select id from payments.plus_invoices where household_id = ?)""").params(household).query(Long.class).single()).isEqualTo(999);
        mvc.perform(get("/api/v1/me/plus").with(TestJwt.customer(ending)))
                .andExpect(jsonPath("$.status").value("none"));
        assertThat(benefits.of(ending).member()).isFalse();
        // a second membership has no trial
        join(ending, "{\"plan\":\"monthly\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void aMembersCart_isPricedWithTheShopsMemberPrices() throws Exception {
        shopFixtures.categories();
        var shop = shopFixtures.shop("Cartville", "Plus Bakery", "master");
        var loaf = shopFixtures.listing(shop, BAKERY, "Members' loaf", 1_000, 10);
        jdbc.sql("update catalogue.offers set member_discount_pct = 15 where id = ?")
                .params(loaf.offerId())
                .update();
        var member = data.user("Member shopper");
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

    // ── Stripe Billing webhooks ──────────────────────────────────────────────────────────────────────────────────

    @Nested
    class Webhooks {

        String household;
        String payer;
        String subscription;

        void stripeMembership() {
            payer = data.user("Stripe payer");
            household = Ids.next();
            subscription = "sub_test_" + Ids.next();
            jdbc.sql("insert into identity.households (id, name, plus_plan) values (?, 'Home', 'monthly')")
                    .params(household)
                    .update();
            jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (?, ?, 'owner')")
                    .params(household, payer)
                    .update();
            jdbc.sql("""
                            insert into loyalty.memberships (household_id, payer_id, plan, status, provider, subscription_id,
                                   current_period_end, trial_used, started_at, updated_at)
                            values (?, ?, 'monthly', 'active', 'stripe', ?, now() + interval '20 days', true, now(), now())""").params(household, payer, subscription).update();
        }

        String subscriptionObject(String status, long periodEnd, boolean cancel) {
            return """
                    {"id":"%s","object":"subscription","status":"%s","cancel_at_period_end":%s,
                     "metadata":{"northline_kind":"plus","northline_household":"%s","northline_plan":"annual"},
                     "items":{"object":"list","data":[{"id":"si_1","object":"subscription_item",
                       "current_period_end":%d}]}}""".formatted(subscription, status, cancel, household, periodEnd);
        }

        String event(String id, String type, Instant created, String object) {
            return """
                    {"id":"%s","object":"event","api_version":"2026-08-26.dahlia","created":%d,"livemode":false,\
                    "type":"%s","pending_webhooks":1,"request":{"id":null,"idempotency_key":null},\
                    "data":{"object":%s}}""".formatted(id, created.getEpochSecond(), type, object);
        }

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

        String membershipStatus() {
            return jdbc.sql("select status from loyalty.memberships where household_id = ?")
                    .params(household)
                    .query(String.class)
                    .single();
        }

        @Test
        void aSubscriptionUpdate_movesTheMembership_once_andInOrder() throws Exception {
            stripeMembership();
            var end = Instant.now().plus(Duration.ofDays(365)).getEpochSecond();
            var payload = event(
                    "evt_" + Ids.next(),
                    "customer.subscription.updated",
                    Instant.now(),
                    subscriptionObject("past_due", end, false));
            deliver(payload, PLATFORM_SECRET)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(false));
            deliver(payload, PLATFORM_SECRET)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(true));
            paymentsJobs.processStripeEvents();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> membershipStatus().equals("past_due"));
            assertThat(jdbc.sql("select plan from loyalty.memberships where household_id = ?")
                            .params(household)
                            .query(String.class)
                            .single())
                    .isEqualTo("annual");
            assertThat(benefits.of(payer).member())
                    .as("retrying: the grace keeps the benefits")
                    .isTrue();

            // an older event arriving late changes nothing
            var older = event(
                    "evt_" + Ids.next(),
                    "customer.subscription.updated",
                    Instant.now().minus(Duration.ofHours(1)),
                    subscriptionObject("active", end, false));
            deliver(older, PLATFORM_SECRET).andExpect(status().isOk());
            paymentsJobs.processStripeEvents();
            Thread.sleep(500);
            assertThat(membershipStatus()).isEqualTo("past_due");

            var deleted = event(
                    "evt_" + Ids.next(),
                    "customer.subscription.deleted",
                    Instant.now().plusSeconds(5),
                    subscriptionObject("canceled", end, false));
            deliver(deleted, PLATFORM_SECRET).andExpect(status().isOk());
            paymentsJobs.processStripeEvents();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> membershipStatus().equals("canceled"));
            assertThat(benefits.of(payer).member()).isFalse();
            mvc.perform(get("/api/v1/me/household").with(TestJwt.customer(payer)))
                    .andExpect(jsonPath("$.plan").value("none"));
        }

        @Test
        void aPaidInvoice_isRevenueOnce() throws Exception {
            stripeMembership();
            var invoice = "in_test_" + Ids.next();
            var object = """
                    {"id":"%s","object":"invoice","status":"paid","amount_paid":1049,
                     "total_taxes":[{"amount":50,"tax_behavior":"exclusive"}],
                     "status_transitions":{"paid_at":%d},
                     "parent":{"type":"subscription_details","subscription_details":{"subscription":"%s",
                       "metadata":{"northline_kind":"plus","northline_household":"%s","northline_plan":"monthly"}}}}""".formatted(invoice, Instant.now().getEpochSecond(), subscription, household);
            var first = event("evt_" + Ids.next(), "invoice.paid", Instant.now(), object);
            var resent =
                    event("evt_" + Ids.next(), "invoice.paid", Instant.now(), object); // Stripe resends as a new event
            deliver(first, PLATFORM_SECRET).andExpect(status().isOk());
            deliver(resent, PLATFORM_SECRET).andExpect(status().isOk());
            paymentsJobs.processStripeEvents();
            var row = jdbc.sql("select id, amount_cents, tax_cents from payments.plus_invoices where invoice_id = ?")
                    .params(invoice)
                    .query((rs, _) -> new long[] {rs.getLong(2), rs.getLong(3)})
                    .list();
            assertThat(row).hasSize(1);
            assertThat(row.getFirst()).containsExactly(1049L, 50L);
            assertThat(jdbc.sql("""
                            select coalesce(sum(credit_cents), 0) from payments.ledger_entries
                             where account = 'revenue' and ref_type = 'plus_invoice'
                               and ref_id = (select id from payments.plus_invoices where invoice_id = ?)""").params(invoice).query(Long.class).single()).isEqualTo(999);
        }

        @Test
        void anUnsignedOrWronglySignedDelivery_isRefused() throws Exception {
            stripeMembership();
            var payload = event(
                    "evt_" + Ids.next(),
                    "customer.subscription.updated",
                    Instant.now(),
                    subscriptionObject("canceled", Instant.now().getEpochSecond(), false));
            deliver(payload, "whsec_test_wrong_fake").andExpect(status().is4xxClientError());
            paymentsJobs.processStripeEvents();
            Thread.sleep(300);
            assertThat(membershipStatus()).isEqualTo("active");
        }
    }

    @Test
    void needsASignIn() throws Exception {
        mvc.perform(get("/api/v1/me/plus")).andExpect(status().isUnauthorized());
    }
}
