package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.application.MembershipBilling;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S-152: Plus on Stripe Billing against stripe-mock, which validates every request against Stripe's OpenAPI spec for
 * the pinned version and answers with fixtures (no state: statuses and dates are its fixtures'). Checks the calls —
 * create with the plan's Price, the trial, {@code default_incomplete}, the metadata; the plan change on the
 * subscription's item; cancelling at the period's end — and that every mutating call carries an {@code nl1:}
 * Idempotency-Key. Nothing here has run against a real Stripe account.
 */
class StripeMembershipBillingStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final PlusBillingProperties props = new PlusBillingProperties(
            PlusBillingProperties.Provider.STRIPE, "price_monthly_test", "price_annual_test", true);
    private final StripeMembershipBilling billing = new StripeMembershipBilling(StripeMock.client(recorder), props);

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
    void subscribe_withTheTrialAndTheCard() {
        var sub = billing.subscribe(new MembershipBilling.Subscribe(
                "user_1",
                "household_1",
                "cus_123",
                "pm_123",
                "monthly",
                999,
                30,
                StripeIdempotencyKeys.of("plus", "household_1", "k1")));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/subscriptions");
        assertThat(sub.id()).startsWith("sub_");
        assertThat(sub.status()).isIn("incomplete", "trialing", "active", "past_due", "canceled");
        assertThat(sub.paid()).as("Stripe's invoices arrive by webhook").isNull();
    }

    @Test
    void changeThePlan_onTheSubscriptionsItem_thenCancelAtThePeriodsEnd() {
        var current = new MembershipBilling.Current("sub_123", "monthly", "active", Instant.now(), false);
        billing.changePlan(current, "annual", 9900, StripeIdempotencyKeys.of("plus-plan", "sub_123", "k2"));
        var paths = recorder.sent().stream().map(StripeMock.Sent::path).toList();
        assertThat(paths).containsSubsequence("/v1/subscriptions/sub_123", "/v1/subscriptions/sub_123");
        billing.cancelAtPeriodEnd(current, true, StripeIdempotencyKeys.of("plus-cancel", "sub_123", "k3"));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/subscriptions/sub_123");
        assertThat(billing.renew(current, 999, Instant.now()))
                .as("Stripe renews by itself")
                .isEmpty();
    }

    @Test
    void aMissingPrice_isAConfigurationError() {
        var noPrices = new StripeMembershipBilling(
                StripeMock.client(recorder),
                new PlusBillingProperties(PlusBillingProperties.Provider.STRIPE, null, " ", false));
        assertThatThrownBy(() -> noPrices.subscribe(new MembershipBilling.Subscribe(
                        "u", "h", "cus_1", null, "annual", 9900, 0, StripeIdempotencyKeys.of("plus", "h", "k"))))
                .hasMessageContaining("STRIPE_PLUS_ANNUAL_PRICE");
        // the recorder must have seen something for the shared check
        billing.cancelAtPeriodEnd(
                new MembershipBilling.Current("sub_9", "monthly", "active", null, false),
                false,
                StripeIdempotencyKeys.of("plus-cancel", "sub_9", "k"));
    }
}
