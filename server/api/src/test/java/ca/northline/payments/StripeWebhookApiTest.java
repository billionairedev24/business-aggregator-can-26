package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeUpdated;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentReauthorizationRequired;
import ca.northline.payments.api.PayoutFailed;
import ca.northline.payments.application.MovePayouts;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.payments.application.RespondToCases;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-12: Stripe webhooks. Deliveries are signed like Stripe signs them ({@code t=…,v1=HMAC-SHA256(t.payload)}) with the
 * test profile's obviously fake secrets and verified by stripe-java's {@code Webhook.constructEvent}. Covers signature
 * and replay checks, dedupe, out-of-order delivery and each handler's effect. Processing is async (outbox listener);
 * the tests also run the retry job, which waits on the row lock, so effects are there when it returns.
 */
@Import({PaymentsFixture.class, StripeWebhookApiTest.Published.class})
class StripeWebhookApiTest extends IntegrationTest {

    static final String PLATFORM_SECRET = "whsec_test_platform_fake";
    static final String CONNECT_SECRET = "whsec_test_connect_fake";

    /** Collects events from any thread (the listener runs async). */
    @TestConfiguration
    static class Published {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(PayoutFailed e) {
            events.add(e);
        }

        @EventListener
        void on(PaymentReauthorizationRequired e) {
            events.add(e);
        }

        @EventListener
        void on(DisputeUpdated e) {
            events.add(e);
        }

        @EventListener
        void on(DisputeDecided e) {
            events.add(e);
        }
    }

    @Autowired
    PaymentsFixture fx;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    PaymentAuthorizations checkout;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    MovePayouts move;

    @Autowired
    CustomerCases customers;

    @Autowired
    RespondToCases respond;

    @Autowired
    Published published;

    @Autowired
    JdbcClient jdbc;

    PaymentsFixture.Shop shop;

    @BeforeEach
    void shop() {
        shop = fx.shop("provider", "master");
    }

    // ── delivery helpers ─────────────────────────────────────────────────────────────────────────────────────────

    static String event(
            String id, String type, Instant created, String object, @Nullable String account, boolean live) {
        return """
                {"id":"%s","object":"event","api_version":"2026-08-26.dahlia","created":%d,"livemode":%s,"type":"%s",%s\
                "pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"data":{"object":%s}}""".formatted(
                        id,
                        created.getEpochSecond(),
                        live,
                        type,
                        account == null ? "" : "\"account\":\"" + account + "\",",
                        object);
    }

    static String sign(String payload, String secret, Instant at) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var t = at.getEpochSecond();
        var v1 = HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + t + ",v1=" + v1;
    }

    ResultActions deliver(String path, String payload, @Nullable String signature) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        return mvc.perform(request);
    }

    /** Delivers a signed platform event and processes it. */
    void platform(String type, Instant created, String object) throws Exception {
        var payload = event("evt_" + Ids.next(), type, created, object, null, false);
        deliver("/api/v1/webhooks/stripe", payload, sign(payload, PLATFORM_SECRET, Instant.now()))
                .andExpect(status().isOk());
        jobs.processStripeEvents();
    }

    void connect(String type, Instant created, String object, String account) throws Exception {
        var payload = event("evt_" + Ids.next(), type, created, object, account, false);
        deliver("/api/v1/webhooks/stripe/connect", payload, sign(payload, CONNECT_SECRET, Instant.now()))
                .andExpect(status().isOk());
        jobs.processStripeEvents();
    }

    String stateOf(String eventId) {
        return jdbc.sql("select state from payments.stripe_events where id = ?")
                .params(eventId)
                .query(String.class)
                .single();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────

    record Held(String escrowId, String bookingId, String customerId, String paymentIntent) {}

    Held held(long cents) {
        var booking = Ids.next();
        var customer = "cust-" + booking;
        var started = checkout.start(new PaymentAuthorizations.Request(
                shop.merchantId(), "booking", booking, customer, cents, 0, "booking:" + booking, null));
        var escrowId = escrows.hold(new EscrowLifecycle.Hold(
                shop.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                booking,
                cents,
                0,
                customer,
                "A. Osei",
                "Inspection",
                null,
                null,
                "Inspection",
                "search",
                started.paymentIntent(),
                Instant.now()));
        return new Held(escrowId, booking, customer, started.paymentIntent());
    }

    Held released(long cents) {
        var held = held(cents);
        escrows.confirmed("booking", held.bookingId(), Instant.now());
        return held;
    }

    String escrowState(String escrowId) {
        return jdbc.sql("select state from payments.escrows where id = ?")
                .params(escrowId)
                .query(String.class)
                .single();
    }

    // ── signatures, replay, dedupe ───────────────────────────────────────────────────────────────────────────────

    @Test
    void signatureIsRequired_andMustBeForThisEndpoint() throws Exception {
        var payload = event("evt_" + Ids.next(), "payout.paid", Instant.now(), "{\"id\":\"po_x\"}", null, false);
        deliver("/api/v1/webhooks/stripe", payload, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_signature"));
        deliver("/api/v1/webhooks/stripe", payload, sign(payload, "whsec_test_wrong_fake", Instant.now()))
                .andExpect(status().isBadRequest());
        // signed with the Connect endpoint's secret, sent to the platform endpoint
        deliver("/api/v1/webhooks/stripe", payload, sign(payload, CONNECT_SECRET, Instant.now()))
                .andExpect(status().isBadRequest());
        // tampered body
        deliver(
                        "/api/v1/webhooks/stripe",
                        payload.replace("po_x", "po_y"),
                        sign(payload, PLATFORM_SECRET, Instant.now()))
                .andExpect(status().isBadRequest());
        deliver("/api/v1/webhooks/stripe/connect", payload, sign(payload, CONNECT_SECRET, Instant.now()))
                .andExpect(status().isOk());
    }

    @Test
    void anOldDelivery_isRefused_evenWithAValidSignature() throws Exception {
        var id = "evt_" + Ids.next();
        var payload = event(id, "payout.paid", Instant.now(), "{\"id\":\"po_x\"}", null, false);
        deliver(
                        "/api/v1/webhooks/stripe",
                        payload,
                        sign(payload, PLATFORM_SECRET, Instant.now().minus(Duration.ofMinutes(10))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("select count(*) from payments.stripe_events where id = ?")
                        .params(id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theSameEventTwice_isStoredAndAppliedOnce() throws Exception {
        fx.credit(shop.merchantId(), 30_000, "escrow", Ids.next(), Instant.now().minusSeconds(3_600));
        var payout =
                move.instant(new MovePayouts.InstantCommand(shop.merchantId(), 10_000, shop.ownerId(), Ids.next()));
        var balance = fx.balance(shop.merchantId());
        var id = "evt_" + Ids.next();
        var payload = event(
                id,
                "payout.failed",
                Instant.now(),
                "{\"id\":\"%s\",\"object\":\"payout\",\"status\":\"failed\",\"failure_code\":\"account_closed\"}"
                        .formatted(payout.getStripePayout()),
                "acct_" + shop.merchantId(),
                false);
        deliver("/api/v1/webhooks/stripe/connect", payload, sign(payload, CONNECT_SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        deliver("/api/v1/webhooks/stripe/connect", payload, sign(payload, CONNECT_SECRET, Instant.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        jobs.processStripeEvents();
        jobs.processStripeEvents();

        assertThat(stateOf(id)).isEqualTo("processed");
        assertThat(fx.balance(shop.merchantId())).isEqualTo(balance + 10_000);
        assertThat(published.events.stream()
                        .filter(e ->
                                e instanceof PayoutFailed f && f.aggregateId().equals(payout.getId())))
                .hasSize(1);
    }

    // ── payouts ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void payoutPaid_thenFailed_returnsTheMoney_andALatePaidChangesNothing() throws Exception {
        fx.credit(shop.merchantId(), 30_000, "escrow", Ids.next(), Instant.now().minusSeconds(3_600));
        var payout =
                move.instant(new MovePayouts.InstantCommand(shop.merchantId(), 20_000, shop.ownerId(), Ids.next()));
        var po = payout.getStripePayout();
        var account = "acct_" + shop.merchantId();
        var t0 = Instant.now().minusSeconds(60);
        var afterPayout = fx.balance(shop.merchantId());

        connect("payout.paid", t0, "{\"id\":\"%s\",\"object\":\"payout\",\"status\":\"paid\"}".formatted(po), account);
        assertThat(payoutState(payout.getId())).isEqualTo("paid");

        connect(
                "payout.failed",
                t0.plusSeconds(30),
                "{\"id\":\"%s\",\"object\":\"payout\",\"status\":\"failed\",\"failure_code\":\"no_account\"}"
                        .formatted(po),
                account);
        assertThat(payoutState(payout.getId())).isEqualTo("failed");
        assertThat(jdbc.sql("select failure_code from payments.payouts where id = ?")
                        .params(payout.getId())
                        .query(String.class)
                        .single())
                .isEqualTo("no_account");
        assertThat(fx.balance(shop.merchantId())).isEqualTo(afterPayout + 20_000);
        // the instant fee recovered from the connected account went back to it
        assertThat(jdbc.sql("select returned_fee_transfer from payments.payouts where id = ?")
                        .params(payout.getId())
                        .query(String.class)
                        .single())
                .startsWith("tr_");

        connect(
                "payout.paid",
                t0.minusSeconds(30),
                "{\"id\":\"%s\",\"object\":\"payout\",\"status\":\"paid\"}".formatted(po),
                account);
        assertThat(payoutState(payout.getId())).isEqualTo("failed");
        assertThat(fx.balance(shop.merchantId())).isEqualTo(afterPayout + 20_000);
    }

    String payoutState(String payoutId) {
        return jdbc.sql("select state from payments.payouts where id = ?")
                .params(payoutId)
                .query(String.class)
                .single();
    }

    // ── disputes ─────────────────────────────────────────────────────────────────────────────────────────────────

    static String dispute(String id, String pi, String status, long amount, Instant dueBy) {
        return """
                {"id":"%s","object":"dispute","payment_intent":"%s","charge":"ch_x","amount":%d,"currency":"cad",\
                "reason":"product_not_received","status":"%s","evidence_details":{"due_by":%d},\
                "evidence":{"customer_name":"Ada Osei","customer_email_address":"ada@example.com"}}""".formatted(id, pi, amount, status, dueBy.getEpochSecond());
    }

    Map<String, @Nullable Object> disputeRow(String stripeDispute) {
        return jdbc.sql("select * from payments.disputes where stripe_dispute = ?")
                .params(stripeDispute)
                .query()
                .singleRow();
    }

    @Test
    void chargeback_opensACase_blocksSettlingIt_andALostOneChargesTheMerchant() throws Exception {
        var held = released(16_000);
        var balance = fx.balance(shop.merchantId());
        var dp = "dp_" + Ids.next();
        var due = Instant.now().plus(Duration.ofDays(10));
        var t0 = Instant.now().minusSeconds(120);

        platform("charge.dispute.created", t0, dispute(dp, held.paymentIntent(), "needs_response", 16_000, due));
        var row = disputeRow(dp);
        assertThat(row.get("state")).isEqualTo("open");
        assertThat(row.get("ref_id")).isEqualTo(held.escrowId());
        assertThat(row.get("stripe_status")).isEqualTo("needs_response");
        assertThat((String) row.get("subject")).startsWith("Card dispute");
        var disputeId = (String) row.get("id");
        // the merchant's team hears about it like any other dispute (S-13 emails)
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> published.events.stream()
                        .anyMatch(e -> e instanceof DisputeUpdated u
                                && u.aggregateId().equals(disputeId)
                                && u.change().equals("opened")));
        // the merchant can't settle a card dispute themselves
        var cases = org.assertj.core.api.Assertions.catchThrowable(
                () -> respond.offerGoodwill(shop.merchantId(), disputeId, 1_000));
        assertThat(cases).isInstanceOf(Conflict.class).hasMessageContaining("card issuer");
        // nothing personal from Stripe's evidence was stored
        assertThat(jdbc.sql(
                                "select count(*) from payments.stripe_events where object_id = ? and payload::text like '%ada%'")
                        .params(dp)
                        .query(Long.class)
                        .single())
                .isZero();

        platform(
                "charge.dispute.updated",
                t0.plusSeconds(10),
                dispute(dp, held.paymentIntent(), "under_review", 16_000, due));
        assertThat(disputeRow(dp).get("stripe_status")).isEqualTo("under_review");

        platform("charge.dispute.closed", t0.plusSeconds(20), dispute(dp, held.paymentIntent(), "lost", 16_000, due));
        row = disputeRow(dp);
        assertThat(row.get("state")).isEqualTo("decided");
        assertThat(row.get("decision")).isEqualTo("full_refund");
        assertThat(published.events.stream()
                        .filter(e -> e instanceof DisputeDecided d
                                && d.aggregateId().equals(disputeId)
                                && d.decidedBy().equals("stripe")))
                .hasSize(1);
        assertThat(fx.balance(shop.merchantId())).isEqualTo(balance - 16_000);
        assertThat(jdbc.sql("select reversed_cents from payments.transfers where escrow_id = ?")
                        .params(held.escrowId())
                        .query(Long.class)
                        .single())
                .isEqualTo(16_000L - 1_440L);

        // a late, older update changes nothing
        platform(
                "charge.dispute.updated",
                t0.plusSeconds(5),
                dispute(dp, held.paymentIntent(), "needs_response", 16_000, due));
        assertThat(disputeRow(dp).get("stripe_status")).isEqualTo("lost");
    }

    @Test
    void chargebackClosedBeforeCreated_endsTheSame_andAWonOneResumesTheEscrow() throws Exception {
        var held = held(9_000);
        escrows.fulfilled("booking", held.bookingId(), Instant.now()); // captured, releasing in 48 h
        var dp = "dp_" + Ids.next();
        var due = Instant.now().plus(Duration.ofDays(10));
        var t0 = Instant.now().minusSeconds(120);

        platform("charge.dispute.closed", t0.plusSeconds(30), dispute(dp, held.paymentIntent(), "won", 9_000, due));
        var row = disputeRow(dp);
        assertThat(row.get("state")).isEqualTo("decided");
        assertThat(row.get("decision")).isEqualTo("release");
        assertThat(escrowState(held.escrowId())).isEqualTo("held");

        platform("charge.dispute.created", t0, dispute(dp, held.paymentIntent(), "needs_response", 9_000, due));
        assertThat(disputeRow(dp).get("state")).isEqualTo("decided");
        assertThat(disputeRow(dp).get("stripe_status")).isEqualTo("won");
        assertThat(escrowState(held.escrowId())).isEqualTo("held");
    }

    @Test
    void chargebackOnACustomersOpenCase_joinsThatCase() throws Exception {
        var held = held(12_000);
        var disputeId = customers.openDispute(held.escrowId(), held.customerId(), "No-show", "Nobody came.");
        var dp = "dp_" + Ids.next();
        platform(
                "charge.dispute.created",
                Instant.now(),
                dispute(
                        dp,
                        held.paymentIntent(),
                        "needs_response",
                        12_000,
                        Instant.now().plus(Duration.ofDays(9))));
        assertThat(disputeRow(dp).get("id")).isEqualTo(disputeId);
    }

    // ── Connect accounts ─────────────────────────────────────────────────────────────────────────────────────────

    static String account(String id, @Nullable String merchantId, boolean payouts, boolean instant, String... due) {
        var list = String.join(
                ",", java.util.Arrays.stream(due).map(d -> "\"" + d + "\"").toList());
        return """
                {"id":"%s","object":"account","type":"express","charges_enabled":true,"payouts_enabled":%s,\
                "metadata":{%s},"requirements":{"currently_due":[%s],"past_due":[%s],"disabled_reason":%s},\
                "individual":{"first_name":"Ravi","dob":{"day":1}},\
                "external_accounts":{"object":"list","data":[{"id":"ba_1","object":"bank_account",\
                "default_for_currency":true,"available_payout_methods":["standard"%s]}]}}""".formatted(
                        id,
                        payouts,
                        merchantId == null ? "" : "\"northline_merchant_id\":\"" + merchantId + "\"",
                        list,
                        payouts ? "" : list,
                        payouts ? "null" : "\"requirements.past_due\"",
                        instant ? ",\"instant\"" : "");
    }

    @Test
    void accountUpdated_pausesPayouts_andLinksTheBusiness_newestEventWins() throws Exception {
        var merchantId = data.merchant("provider", "Stripe Linked");
        var acct = "acct_" + Ids.next();
        var t0 = Instant.now().minusSeconds(60);

        connect("account.updated", t0, account(acct, merchantId, false, true, "external_account"), acct);
        var row = jdbc.sql("select * from payments.connected_accounts where merchant_id = ?")
                .params(merchantId)
                .query()
                .singleRow();
        assertThat(row.get("stripe_account")).isEqualTo(acct);
        assertThat(row.get("payouts_enabled")).isEqualTo(false);
        assertThat(row.get("instant_payouts")).isEqualTo(true);
        assertThat(row.get("requirements_past_due")).isEqualTo(1);
        assertThat(row.get("disabled_reason")).isEqualTo("requirements.past_due");
        // merchants links the business to the account (async listener)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(jdbc.sql("select stripe_account_id from merchants.merchants where id = ?")
                                        .params(merchantId)
                                        .query(String.class)
                                        .single())
                                .isEqualTo(acct));

        connect("account.updated", t0.plusSeconds(20), account(acct, merchantId, true, false), acct);
        connect("account.updated", t0.plusSeconds(10), account(acct, merchantId, false, true, "x"), acct);
        row = jdbc.sql("select * from payments.connected_accounts where merchant_id = ?")
                .params(merchantId)
                .query()
                .singleRow();
        assertThat(row.get("payouts_enabled")).isEqualTo(true);
        assertThat(row.get("instant_payouts")).isEqualTo(false);
        assertThat(row.get("requirements_due")).isEqualTo(0);
    }

    @Test
    void payoutsPausedByStripe_refuseInstantPayouts() throws Exception {
        var acct = "acct_" + shop.merchantId();
        fx.credit(shop.merchantId(), 30_000, "escrow", Ids.next(), Instant.now().minusSeconds(3_600));
        connect("account.updated", Instant.now(), account(acct, null, false, true, "external_account"), acct);
        var refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                move.instant(new MovePayouts.InstantCommand(shop.merchantId(), 5_000, shop.ownerId(), Ids.next())));
        assertThat(refused).isInstanceOf(Conflict.class).hasMessageContaining("paused payouts");
    }

    // ── charges, refunds, transfers ──────────────────────────────────────────────────────────────────────────────

    @Test
    void paymentIntentEvents_moveTheMirrorForwardOnly_andALapsedHoldAsksTheCustomer() throws Exception {
        var held = held(5_000);
        var pi = held.paymentIntent();
        var t0 = Instant.now().minusSeconds(60);
        platform(
                "payment_intent.canceled",
                t0,
                "{\"id\":\"%s\",\"object\":\"payment_intent\",\"status\":\"canceled\"}".formatted(pi));
        assertThat(intentState(pi)).isEqualTo("canceled");
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> published.events.stream()
                        .anyMatch(e -> e instanceof PaymentReauthorizationRequired r
                                && r.aggregateId().equals(held.escrowId())));
        // a late amount_capturable_updated can't bring it back
        platform(
                "payment_intent.amount_capturable_updated",
                t0.minusSeconds(30),
                "{\"id\":\"%s\",\"object\":\"payment_intent\",\"status\":\"requires_capture\"}".formatted(pi));
        assertThat(intentState(pi)).isEqualTo("canceled");

        var other = held(7_000);
        jdbc.sql("update payments.payment_intents set state = 'requires_action' where stripe_pi = ?")
                .params(other.paymentIntent())
                .update();
        platform(
                "payment_intent.amount_capturable_updated",
                t0,
                "{\"id\":\"%s\",\"object\":\"payment_intent\",\"status\":\"requires_capture\"}"
                        .formatted(other.paymentIntent()));
        assertThat(intentState(other.paymentIntent())).isEqualTo("authorized");
        platform(
                "payment_intent.succeeded",
                t0.plusSeconds(5),
                "{\"id\":\"%s\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"latest_charge\":\"ch_w1\"}"
                        .formatted(other.paymentIntent()));
        assertThat(intentState(other.paymentIntent())).isEqualTo("captured");
        platform(
                "payment_intent.payment_failed",
                t0.plusSeconds(10),
                "{\"id\":\"%s\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\"}"
                        .formatted(other.paymentIntent()));
        assertThat(intentState(other.paymentIntent())).isEqualTo("captured");
    }

    String intentState(String pi) {
        return jdbc.sql("select state from payments.payment_intents where stripe_pi = ?")
                .params(pi)
                .query(String.class)
                .single();
    }

    @Test
    void refundAndTransferEvents_updateTheMirror_unknownOnesAreIgnored() throws Exception {
        var held = released(16_000);
        var refundId = customers.requestRefund(held.escrowId(), held.customerId(), 3_000, "Scratched");
        respond.acceptRefund(shop.merchantId(), refundId);
        jobs.payRefundQueue();
        var re = jdbc.sql("select stripe_refund from payments.refunds where id = ?")
                .params(refundId)
                .query(String.class)
                .single();
        platform(
                "refund.failed",
                Instant.now(),
                "{\"id\":\"%s\",\"object\":\"refund\",\"status\":\"failed\",\"failure_reason\":\"expired_or_canceled_card\"}"
                        .formatted(re));
        assertThat(jdbc.sql("select stripe_status from payments.refunds where id = ?")
                        .params(refundId)
                        .query(String.class)
                        .single())
                .isEqualTo("failed");

        var tr = jdbc.sql("select stripe_transfer from payments.transfers where escrow_id = ?")
                .params(held.escrowId())
                .query(String.class)
                .single();
        platform(
                "transfer.reversed",
                Instant.now(),
                "{\"id\":\"%s\",\"object\":\"transfer\",\"amount_reversed\":4000}".formatted(tr));
        assertThat(jdbc.sql("select reversed_cents from payments.transfers where escrow_id = ?")
                        .params(held.escrowId())
                        .query(Long.class)
                        .single())
                .isEqualTo(4_000L);

        var unknown = "evt_" + Ids.next();
        var payload = event(
                unknown,
                "customer.created",
                Instant.now(),
                "{\"id\":\"cus_1\",\"object\":\"customer\",\"email\":\"someone@example.com\"}",
                null,
                false);
        deliver("/api/v1/webhooks/stripe", payload, sign(payload, PLATFORM_SECRET, Instant.now()))
                .andExpect(status().isOk());
        jobs.processStripeEvents();
        assertThat(stateOf(unknown)).isEqualTo("ignored");
        assertThat(jdbc.sql("select payload::text from payments.stripe_events where id = ?")
                        .params(unknown)
                        .query(String.class)
                        .single())
                .doesNotContain("someone@example.com");
    }

    @Test
    void liveModeEvents_areIgnoredByATestModeInstallation() throws Exception {
        var id = "evt_" + Ids.next();
        var payload = event(id, "payout.paid", Instant.now(), "{\"id\":\"po_live\"}", null, true);
        deliver("/api/v1/webhooks/stripe", payload, sign(payload, PLATFORM_SECRET, Instant.now()))
                .andExpect(status().isOk());
        jobs.processStripeEvents();
        assertThat(stateOf(id)).isEqualTo("ignored");
    }
}
