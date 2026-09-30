package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.api.ConnectedAccounts;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentReauthorizationRequired;
import ca.northline.payments.api.PayoutPlan;
import ca.northline.payments.application.MovePayouts;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.payments.application.RespondToCases;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-11 rules on the fake gateway: checkout opens the manual-capture PaymentIntent, a hold must be authorized, capture
 * keeps the charge, the transfer carries the transfer group, holds about to lapse are renewed (or the customer is asked
 * to confirm again), a refund before capture cancels the hold, a refund of released money reverses the transfer, the
 * instant payout fee is recovered, and Connect accounts link with manual payouts.
 */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class StripeEscrowTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    PaymentAuthorizations checkout;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    CustomerCases customers;

    @Autowired
    RespondToCases respond;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    MovePayouts move;

    @Autowired
    ConnectedAccounts connected;

    @Autowired
    PayoutPlan plan;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    private record Held(String escrowId, String bookingId, String customerId, String paymentIntent) {}

    private Held checkoutAndHold(String merchantId, long cents) {
        var booking = Ids.next();
        var customer = "cust-" + Ids.next();
        var started = checkout.start(new PaymentAuthorizations.Request(
                merchantId, "booking", booking, customer, cents, Math.round(cents * 0.05), "booking:" + booking, null));
        var escrowId = escrows.hold(hold(merchantId, booking, customer, cents, started.paymentIntent()));
        return new Held(escrowId, booking, customer, started.paymentIntent());
    }

    private static EscrowLifecycle.Hold hold(
            String merchantId, String booking, String customer, long cents, String paymentIntent) {
        return new EscrowLifecycle.Hold(
                merchantId,
                EscrowKind.SERVICE,
                "booking",
                booking,
                cents,
                Math.round(cents * 0.05),
                customer,
                "D. Kowalski",
                "Brake pads",
                null,
                null,
                "Brake pads",
                "search",
                paymentIntent,
                Instant.now());
    }

    private Map<String, @Nullable Object> intentOfEscrow(String escrowId) {
        return jdbc.sql("""
                        select p.* from payments.payment_intents p
                          join payments.escrows e on e.payment_intent_id = p.id where e.id = ?""").params(escrowId).query().singleRow();
    }

    @Test
    void checkout_opensAManualCapturePaymentIntent_withACustomerAndTheTransferGroup() {
        var shop = fx.shop("provider", "master");
        var held = checkoutAndHold(shop.merchantId(), 24_700);
        var intent = intentOfEscrow(held.escrowId());
        assertThat(intent.get("stripe_pi")).isEqualTo(held.paymentIntent());
        assertThat(intent.get("state")).isEqualTo("authorized");
        assertThat(intent.get("capture_method")).isEqualTo("manual");
        assertThat(intent.get("amount_cents")).isEqualTo(24_700L + 1_235L);
        assertThat((String) intent.get("stripe_customer")).startsWith("cus_");
        assertThat(intent.get("transfer_group")).isEqualTo("booking:" + held.bookingId());
        assertThat(intent.get("authorized_at")).isNotNull();
        assertThat(intent.get("capture_before")).isNotNull();
        // the customer's Stripe Customer is reused
        var again = checkout.start(new PaymentAuthorizations.Request(
                shop.merchantId(), "booking", Ids.next(), held.customerId(), 1_000, 0, "booking:x", null));
        assertThat(jdbc.sql("select stripe_customer from payments.payment_intents where stripe_pi = ?")
                        .params(again.paymentIntent())
                        .query(String.class)
                        .single())
                .isEqualTo(intent.get("stripe_customer"));
    }

    @Test
    void hold_needsAnAuthorizedPaymentIntent() {
        var shop = fx.shop("provider", "master");
        assertThatThrownBy(() -> escrows.hold(
                        hold(shop.merchantId(), Ids.next(), "cust-x", 5_000, "pi_requires_action_" + Ids.next())))
                .isInstanceOf(Conflict.class)
                .hasMessageContaining("isn't authorized");
    }

    @Test
    void capture_keepsTheCharge_andTheTransferUsesTheGroupAndTheNet() {
        var shop = fx.shop("provider", "master");
        var held = checkoutAndHold(shop.merchantId(), 24_700);
        escrows.confirmed("booking", held.bookingId(), Instant.now());
        var intent = intentOfEscrow(held.escrowId());
        assertThat(intent.get("state")).isEqualTo("captured");
        assertThat((String) intent.get("stripe_charge")).startsWith("ch_");
        var transfer = jdbc.sql("select * from payments.transfers where escrow_id = ?")
                .params(held.escrowId())
                .query()
                .singleRow();
        assertThat((String) transfer.get("stripe_transfer")).startsWith("tr_");
        assertThat(transfer.get("transfer_group")).isEqualTo("booking:" + held.bookingId());
        assertThat(transfer.get("net_cents")).isEqualTo(24_700L - 2_223L);
    }

    @Test
    void aHoldAboutToLapse_isRenewed_beforeTheOldOneIsCanceled() {
        var shop = fx.shop("provider", "master");
        var held = checkoutAndHold(shop.merchantId(), 12_000);
        var before = intentOfEscrow(held.escrowId());
        jdbc.sql("update payments.payment_intents set capture_before = now() + interval '2 hours' where id = ?")
                .params(before.get("id"))
                .update();

        assertThat(jobs.renewAuthorizations()).isGreaterThanOrEqualTo(1);

        var after = intentOfEscrow(held.escrowId());
        assertThat(after.get("id")).isNotEqualTo(before.get("id"));
        assertThat(after.get("state")).isEqualTo("authorized");
        assertThat(after.get("reauthorizations")).isEqualTo(1);
        assertThat(after.get("stripe_customer")).isEqualTo(before.get("stripe_customer"));
        assertThat(after.get("transfer_group")).isEqualTo(before.get("transfer_group"));
        var old = jdbc.sql("select state, replaced_by from payments.payment_intents where id = ?")
                .params(before.get("id"))
                .query()
                .singleRow();
        assertThat(old.get("state")).isEqualTo("canceled");
        assertThat(old.get("replaced_by")).isEqualTo(after.get("id"));
        // the renewed hold is what gets captured
        escrows.confirmed("booking", held.bookingId(), Instant.now());
        assertThat(intentOfEscrow(held.escrowId()).get("state")).isEqualTo("captured");
    }

    @Test
    void aHoldThatCantBeRenewed_asksTheCustomerOnce_andIsRetriedLater() {
        var shop = fx.shop("provider", "master");
        var booking = Ids.next();
        // held without checkout: no saved card to renew with
        var escrowId = escrows.hold(hold(shop.merchantId(), booking, "cust-" + booking, 8_000, "pi_" + booking));
        var intentId = intentOfEscrow(escrowId).get("id");
        jdbc.sql("update payments.payment_intents set capture_before = now() + interval '3 hours' where id = ?")
                .params(intentId)
                .update();

        jobs.renewAuthorizations();
        jobs.renewAuthorizations();

        assertThat(events.stream(PaymentReauthorizationRequired.class)
                        .filter(e -> e.aggregateId().equals(escrowId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.refId()).isEqualTo(booking);
                    assertThat(e.customerId()).isEqualTo("cust-" + booking);
                });
        var intent = intentOfEscrow(escrowId);
        assertThat(intent.get("id")).isEqualTo(intentId);
        assertThat(intent.get("reauth_failed_at")).isNotNull();
    }

    @Test
    void aFullRefundBeforeCapture_cancelsTheHold_andPostsNothing() {
        var shop = fx.shop("provider", "master");
        var held = checkoutAndHold(shop.merchantId(), 30_000);
        var refundId = customers.requestRefund(held.escrowId(), held.customerId(), 30_000, "Job cancelled");
        respond.acceptRefund(shop.merchantId(), refundId);
        jobs.payRefundQueue();

        assertThat(intentOfEscrow(held.escrowId()).get("state")).isEqualTo("canceled");
        var refund = jdbc.sql("select state, stripe_refund from payments.refunds where id = ?")
                .params(refundId)
                .query()
                .singleRow();
        assertThat(refund.get("state")).isEqualTo("paid");
        assertThat(refund.get("stripe_refund")).isNull();
        assertThat(jdbc.sql("select count(*) from payments.ledger_entries where ref_id in (?, ?)")
                        .params(refundId, held.escrowId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(fx.balance(shop.merchantId())).isZero();
    }

    @Test
    void aRefundOfReleasedMoney_refundsTheCard_andReversesThatMuchOfTheTransfer() {
        var shop = fx.shop("provider", "master");
        var held = checkoutAndHold(shop.merchantId(), 16_000);
        escrows.confirmed("booking", held.bookingId(), Instant.now());
        var refundId = customers.requestRefund(held.escrowId(), held.customerId(), 5_000, "Wiper blade wrong size");
        respond.acceptRefund(shop.merchantId(), refundId);
        jobs.payRefundQueue();

        var refund = jdbc.sql(
                        "select stripe_refund, stripe_transfer_reversal, reversed_cents from payments.refunds where id = ?")
                .params(refundId)
                .query()
                .singleRow();
        assertThat((String) refund.get("stripe_refund")).startsWith("re_");
        assertThat((String) refund.get("stripe_transfer_reversal")).startsWith("trr_");
        assertThat(refund.get("reversed_cents")).isEqualTo(5_000L);
        assertThat(jdbc.sql("select reversed_cents from payments.transfers where escrow_id = ?")
                        .params(held.escrowId())
                        .query(Long.class)
                        .single())
                .isEqualTo(5_000L);
        // the ledger agrees: the merchant carries the refund, Northline keeps its fee
        assertThat(fx.balance(shop.merchantId())).isEqualTo(16_000 - 1_440 - 5_000);
    }

    @Test
    void instantPayout_recoversTheFeeFromTheConnectedAccount() {
        var shop = fx.shop("provider", "master");
        fx.credit(shop.merchantId(), 20_000, "escrow", Ids.next(), Instant.now().minusSeconds(3_600));
        var payout =
                move.instant(new MovePayouts.InstantCommand(shop.merchantId(), 10_000, shop.ownerId(), Ids.next()));
        assertThat(payout.getFeeCents()).isEqualTo(100);
        assertThat(payout.getStripeFeeTransfer()).startsWith("tr_");
        assertThat(jdbc.sql("select stripe_fee_transfer from payments.payouts where id = ?")
                        .params(payout.getId())
                        .query(String.class)
                        .single())
                .startsWith("tr_");
    }

    @Test
    void connectAccounts_areLinkedOnce_andPayoutPlanIsNorthlines() {
        var merchantId = fx.shop("provider", "master").merchantId();
        var fresh = Ids.next();
        assertThat(plan.of(fresh)).isEmpty();
        connected.linked(fresh, "acct_" + fresh);
        connected.linked(fresh, "acct_" + fresh);
        assertThat(jdbc.sql("select count(*) from payments.connected_accounts where merchant_id = ?")
                        .params(fresh)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(plan.of(fresh)).hasValueSatisfying(p -> {
            assertThat(p.interval()).isEqualTo("weekly");
            assertThat(p.weekday()).isEqualTo("friday");
            assertThat(p.instantPayouts()).isFalse();
        });
        assertThat(plan.of(merchantId))
                .hasValueSatisfying(p -> assertThat(p.instantPayouts()).isTrue());
    }
}
