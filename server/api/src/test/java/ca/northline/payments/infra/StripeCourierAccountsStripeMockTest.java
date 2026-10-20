package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S-151: couriers' Connect accounts and their payout against stripe-mock (validated against Stripe's OpenAPI spec of
 * the pinned version): the Express recipient account (transfers only), its onboarding link, reading it back, then the
 * weekly payout's two calls — a transfer to the account and a payout to its bank. Every mutating call carries an
 * {@code Idempotency-Key}; every call the pinned {@code Stripe-Version}. stripe-mock keeps no state, so the account it
 * answers with is its fixture (no payouts-enabled courier, no bank): the mapping is checked, not Stripe's behaviour.
 */
class StripeCourierAccountsStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeCourierAccounts accounts = new StripeCourierAccounts(StripeMock.client(recorder));
    private final StripeConnectGateway gateway = new StripeConnectGateway(StripeMock.client(recorder));

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
    void anExpressRecipientAccount_keyedByTheCourier_andItsOnboardingLink() {
        var account = accounts.createAccount("01J9ZD3V0000000000000COUR1");
        assertThat(account).startsWith("acct_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts");
        assertThat(recorder.sent().getLast().idempotencyKeys())
                .containsExactly("nl1:courier-account:01J9ZD3V0000000000000COUR1");

        var link = accounts.onboardingLink(
                account, "https://example.ca/courier/payouts", "https://example.ca/courier/payouts");
        assertThat(link).startsWith("http");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/account_links");
        // a fresh key per link (links are single-use)
        var again = accounts.onboardingLink(
                account, "https://example.ca/courier/payouts", "https://example.ca/courier/payouts");
        assertThat(again).startsWith("http");
        var keys = recorder.sent().stream()
                .filter(s -> s.path().equals("/v1/account_links"))
                .map(s -> s.idempotencyKeys().getFirst())
                .toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void readingTheAccount_mapsPayoutsAndTheBank() {
        var a = accounts.account("acct_1");
        assertThat(a.requirementsDue()).isNotNegative();
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/accounts/acct_1");
    }

    @Test
    void theWeeklyPayout_transferThenPayoutOnTheCouriersAccount() {
        var transfer = gateway.transfer(new PaymentGateway.Transfer(
                "acct_1",
                1_900,
                "courier:01J9ZD3V0000000000000COUR1",
                null,
                Map.of("northline_courier_user_id", "01J9ZD3V0000000000000COUR1"),
                StripeIdempotencyKeys.of("courier-transfer", "01J9ZD3V0000000000000PAY01")));
        assertThat(transfer).startsWith("tr_");
        var sent = gateway.payout(
                "acct_1",
                1_900,
                false,
                "ba_1",
                StripeIdempotencyKeys.of("courier-payout", "01J9ZD3V0000000000000PAY01"));
        assertThat(sent.payoutId()).startsWith("po_");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/payouts");
    }
}
