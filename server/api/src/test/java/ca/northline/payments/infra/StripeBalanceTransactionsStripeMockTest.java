package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.stripe.StripeClients;
import ca.northline.support.StripeMock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * S-85: the platform's balance transactions against stripe-mock (its fixtures, no state): the list call, the created
 * range and the pinned version. Nothing here has run against a real Stripe account.
 */
class StripeBalanceTransactionsStripeMockTest {

    private final StripeMock.Recorder recorder = new StripeMock.Recorder();
    private final StripeBalanceTransactions balance = new StripeBalanceTransactions(StripeMock.client(recorder));

    @Test
    void listsTheDaysBalanceTransactions() {
        var txns = balance.between(Instant.parse("2026-09-08T06:00:00Z"), Instant.parse("2026-09-09T06:00:00Z"));
        assertThat(recorder.sent()).isNotEmpty();
        assertThat(recorder.sent().getFirst().path()).isEqualTo("/v1/balance_transactions");
        assertThat(recorder.sent()).allSatisfy(s -> assertThat(s.version()).isEqualTo(StripeClients.PINNED_API_VERSION));
        assertThat(txns).allSatisfy(t -> {
            assertThat(t.id()).startsWith("txn_");
            assertThat(t.type()).isNotBlank();
        });
    }
}
