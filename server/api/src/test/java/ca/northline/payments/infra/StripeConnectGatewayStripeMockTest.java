package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.domain.Payout;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Every call path of the payments Stripe adapter against stripe-mock (which validates each request against Stripe's
 * OpenAPI spec for the pinned version): the escrow flow hold → capture → transfer → payout, cancel, refund + transfer
 * reversal, instant payout + fee recovery, payout reconciliation and the default bank account. Every mutating call must carry an
 * {@code Idempotency-Key} and every call the pinned {@code Stripe-Version}.
 */
class StripeConnectGatewayStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeConnectGateway gateway = new StripeConnectGateway(StripeMock.client(recorder));

    private static final Map<String, String> IDS =
            Map.of("northline_escrow_id", "01J9ZD3V00000000000000ESC1", "northline_merchant_id", "PWM1");

    @AfterEach
    void everyMutatingCallHadAnIdempotencyKey_andThePinnedVersion() {
        var sent = recorder.sent();
        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(sent.stream().filter(StripeMock.Sent::mutating))
                .allSatisfy(s -> assertThat(s.idempotencyKeys())
                        .as("%s %s", s.method(), s.path())
                        .singleElement()
                        .satisfies(k -> assertThat(k).startsWith("nl1:")));
    }

    @Test
    void escrowFlow_holdCaptureTransferPayout() {
        var customer = gateway.customer("01J9ZD3V00000000000000CUS1", StripeIdempotencyKeys.of("customer", "CUS1"));
        assertThat(customer).startsWith("cus_");

        var hold = gateway.authorize(new PaymentGateway.Authorize(
                25_935,
                "booking:BK1",
                customer,
                null,
                false,
                IDS,
                StripeIdempotencyKeys.of("authorize", "booking", "BK1")));
        assertThat(hold.paymentIntent()).startsWith("pi_");
        assertThat(hold.clientSecret()).contains("_secret_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/payment_intents");

        var read = gateway.authorization(hold.paymentIntent());
        assertThat(read.paymentIntent()).startsWith("pi_");
        assertThat(read.status()).isIn(IntentStatus.values());

        var charge = gateway.capture(hold.paymentIntent(), 25_935, StripeIdempotencyKeys.of("capture", "ESC1", "PI1"));
        assertThat(recorder.sent().getLast().path()).endsWith("/capture");

        var transfer = gateway.transfer(new PaymentGateway.Transfer(
                "acct_1Kx9PWM0000000Q2",
                22_477,
                "booking:BK1",
                charge,
                IDS,
                StripeIdempotencyKeys.of("transfer", "ESC1")));
        assertThat(transfer).startsWith("tr_");

        var sent = gateway.payout(
                "acct_1Kx9PWM0000000Q2",
                22_477,
                false,
                "ba_test",
                StripeIdempotencyKeys.of("scheduled-payout", "PWM1", "2026-10-02"));
        assertThat(sent.payoutId()).startsWith("po_");
        assertThat(sent.arrivesAt()).isNotNull();
    }

    @Test
    void reauthorization_confirmsOffSession_andCancelReleasesAHold() {
        var renewed = gateway.authorize(new PaymentGateway.Authorize(
                10_000,
                "order:O1",
                "cus_test",
                "pm_card_visa",
                true,
                IDS,
                StripeIdempotencyKeys.of("reauthorize", "E1", "1")));
        assertThat(renewed.paymentIntent()).startsWith("pi_");
        gateway.cancel(renewed.paymentIntent(), StripeIdempotencyKeys.of("cancel", "PI1"));
        assertThat(recorder.sent().getLast().path()).endsWith("/cancel");
    }

    @Test
    void refundOfReleasedMoney_refundsTheCard_andReversesTheTransfer() {
        var refund = gateway.refund("pi_123", 8_000, IDS, StripeIdempotencyKeys.of("refund", "RF1"));
        assertThat(refund).startsWith("re_");
        var reversal =
                gateway.reverseTransfer("tr_123", 8_000, IDS, StripeIdempotencyKeys.of("reverse-transfer", "RF1"));
        assertThat(reversal).startsWith("trr_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/transfers/tr_123/reversals");
    }

    @Test
    void instantPayout_thenTheFeeIsDebitedFromTheConnectedAccount() {
        var sent = gateway.payout(
                "acct_1",
                9_900,
                true,
                "card_123",
                StripeIdempotencyKeys.fromClient("instant-payout", "PWM1:RAV1", "client-key-1"));
        var fee = gateway.recoverFee(
                "acct_1", 100, sent.payoutId(), StripeIdempotencyKeys.of("instant-payout-fee", sent.payoutId()));
        assertThat(fee).startsWith("tr_");
        // the platform account is read once, then transfers from the connected account go to it
        assertThat(recorder.sent()).anyMatch(s -> s.path().equals("/v1/account"));
        gateway.recoverFee("acct_1", 100, "po_2", StripeIdempotencyKeys.of("instant-payout-fee", "po_2"));
        assertThat(recorder.sent().stream().filter(s -> s.path().equals("/v1/account")))
                .hasSize(1);
    }

    @Test
    void payoutReconciliation_readsTheStateOnTheConnectedAccount() {
        assertThat(gateway.payoutState("acct_1", "po_123")).isIn(Payout.State.values());
        assertThat(StripeConnectGateway.payoutState("paid")).isEqualTo(Payout.State.PAID);
        assertThat(StripeConnectGateway.payoutState("failed")).isEqualTo(Payout.State.FAILED);
        assertThat(StripeConnectGateway.payoutState("canceled")).isEqualTo(Payout.State.CANCELED);
        assertThat(StripeConnectGateway.payoutState("in_transit")).isEqualTo(Payout.State.IN_TRANSIT);
        assertThat(StripeConnectGateway.status("requires_capture")).isEqualTo(IntentStatus.AUTHORIZED);
        assertThat(StripeConnectGateway.status("succeeded")).isEqualTo(IntentStatus.CAPTURED);
        assertThat(StripeConnectGateway.status("requires_action")).isEqualTo(IntentStatus.REQUIRES_ACTION);
        assertThat(StripeConnectGateway.status("canceled")).isEqualTo(IntentStatus.CANCELED);
    }

    @Test
    void connectedAccount_manualPayouts_andDefaultBankAccount() {
        gateway.useManualPayouts("acct_1");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts/acct_1");
        gateway.makeDefault("acct_1", "ba_123"); // linking itself: StripeBankLinkingStripeMockTest
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts/acct_1/external_accounts/ba_123");
    }
}
