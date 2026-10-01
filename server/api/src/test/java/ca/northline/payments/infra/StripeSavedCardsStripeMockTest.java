package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import ca.northline.support.StripeMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * S-59: saved cards against stripe-mock, which validates every request against Stripe's OpenAPI spec for the pinned
 * version and answers with fixtures (it keeps no state). Checks the requests — SetupIntent for cards, off-session; card
 * PaymentMethods of the Customer; the default through {@code invoice_settings}; detach — and that every mutating call
 * carries an {@code nl1:} Idempotency-Key. Nothing here has run against a real Stripe account.
 */
class StripeSavedCardsStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeSavedCards cards = new StripeSavedCards(StripeMock.client(recorder));

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
    void setupIntentForTheCustomer() {
        var intent = cards.createSetupIntent("cus_123", StripeIdempotencyKeys.of("setup", "u1", "1"));
        assertThat(intent.id()).startsWith("seti_");
        assertThat(intent.clientSecret()).isNotBlank();
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/setup_intents");
        var read = cards.setupIntent(intent.id());
        assertThat(read.id()).startsWith("seti_");
        assertThat(recorder.sent().getLast().path()).startsWith("/v1/setup_intents/");
    }

    @Test
    void theCustomersCards_brandLast4AndExpiryOnly() {
        var list = cards.cards("cus_123");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/customers/cus_123/payment_methods");
        assertThat(list).allSatisfy(c -> {
            assertThat(c.paymentMethod()).startsWith("pm_");
            assertThat(c.last4()).hasSize(4);
        });
    }

    @Test
    void defaultAndDetach() {
        cards.defaultCard("cus_123");
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/customers/cus_123");
        cards.makeDefault("cus_123", "pm_123", StripeIdempotencyKeys.of("default", "u1", "2"));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/customers/cus_123");
        cards.detach("pm_123", StripeIdempotencyKeys.of("detach", "pm_123"));
        assertThat(recorder.sent().getLast().path()).isEqualTo("/v1/payment_methods/pm_123/detach");
    }
}
