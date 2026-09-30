package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.application.MovePayouts;
import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.payments.application.RespondToCases;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.StripeMock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The S-11 acceptance flow through the real Stripe adapter against stripe-mock ({@code STRIPE_API_BASE}): checkout →
 * hold → capture → transfer → instant payout (+ fee) → refund with transfer reversal. stripe-mock keeps no state, so
 * the one read whose answer depends on a customer confirming a card (the PaymentIntent being {@code requires_capture})
 * is stubbed; every other call is a real HTTP request validated by stripe-mock.
 */
@Import(PaymentsFixture.class)
class StripeMockFlowTest extends IntegrationTest {

    @DynamicPropertySource
    static void stripeMock(DynamicPropertyRegistry registry) {
        registry.add("northline.payments.stripe-secret-key", () -> StripeMock.SECRET_KEY);
        registry.add("northline.payments.stripe-publishable-key", () -> "pk_test_fake");
        registry.add("northline.payments.stripe-api-base", StripeMock::apiBase);
        registry.add("northline.stripe.secret-key", () -> StripeMock.SECRET_KEY);
        registry.add("northline.stripe.api-base", StripeMock::apiBase);
    }

    @MockitoSpyBean
    PaymentGateway gateway;

    @Autowired
    PaymentsFixture fx;

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
    PaymentsJobs jobs;

    @Autowired
    JdbcClient jdbc;

    @Test
    void holdCaptureTransferPayout_andARefundWithReversal_throughStripeMock() {
        assertThat(gateway.getClass().getName()).contains("StripeConnectGateway");
        var shop = fx.shop("provider", "master");
        var booking = Ids.next();
        var customer = "cust-" + booking;

        var started = checkout.start(new PaymentAuthorizations.Request(
                shop.merchantId(), "booking", booking, customer, 16_000, 800, "booking:" + booking, Ids.next()));
        assertThat(started.paymentIntent()).startsWith("pi_");
        assertThat(started.clientSecret()).contains("_secret_");

        // the customer confirmed the card with Stripe.js: Stripe now reports the hold
        doReturn(new PaymentGateway.Authorization(
                        started.paymentIntent(),
                        IntentStatus.AUTHORIZED,
                        16_800,
                        16_800,
                        null,
                        "cus_test",
                        "pm_card_visa",
                        "ch_test",
                        Instant.now().plus(Duration.ofDays(7)),
                        "booking:" + booking))
                .when(gateway)
                .authorization(started.paymentIntent());

        var escrowId = escrows.hold(new EscrowLifecycle.Hold(
                shop.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                booking,
                16_000,
                800,
                customer,
                "A. Osei",
                "Pre-purchase inspection",
                null,
                null,
                "Pre-purchase inspection",
                "search",
                started.paymentIntent(),
                Instant.now()));
        escrows.fulfilled("booking", booking, Instant.now());
        escrows.confirmed("booking", booking, Instant.now());

        assertThat(jdbc.sql("select state from payments.escrows where id = ?")
                        .params(escrowId)
                        .query(String.class)
                        .single())
                .isEqualTo("released");
        var transfer = jdbc.sql(
                        "select stripe_transfer, net_cents, transfer_group from payments.transfers where escrow_id = ?")
                .params(escrowId)
                .query()
                .singleRow();
        assertThat((String) transfer.get("stripe_transfer")).startsWith("tr_");
        assertThat(transfer.get("net_cents")).isEqualTo(16_000L - 1_440L);
        assertThat(transfer.get("transfer_group")).isEqualTo("booking:" + booking);

        var payout =
                move.instant(new MovePayouts.InstantCommand(shop.merchantId(), 10_000, shop.ownerId(), Ids.next()));
        assertThat(payout.getStripePayout()).startsWith("po_");
        assertThat(payout.getStripeFeeTransfer()).startsWith("tr_");

        var refundId = customers.requestRefund(escrowId, customer, 4_000, "Missed a check");
        respond.acceptRefund(shop.merchantId(), refundId);
        jobs.payRefundQueue();
        var refund = jdbc.sql(
                        "select state, stripe_refund, stripe_transfer_reversal from payments.refunds where id = ?")
                .params(refundId)
                .query()
                .singleRow();
        assertThat(refund.get("state")).isEqualTo("paid");
        assertThat((String) refund.get("stripe_refund")).startsWith("re_");
        assertThat((String) refund.get("stripe_transfer_reversal")).startsWith("trr_");

        // the scheduled-payout reconciler asks Stripe for the state
        assertThat(jobs.runPayouts()).isGreaterThanOrEqualTo(0);
    }
}
