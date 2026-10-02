package ca.northline.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.api.PayoutFailed;
import ca.northline.payments.api.PayoutSent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** S-111: checkouts and payouts counted once committed, by outcome — never by merchant. */
class PaymentMetricsTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final PaymentMetrics metrics = new PaymentMetrics(meters);
    final Instant now = Instant.parse("2026-10-01T18:00:00Z");

    @Test
    void countsPayoutsAndTheirAmounts() {
        metrics.on(new PayoutSent("e1", now, "po_1", "m_1", "instant", 12_345, 125, now));
        metrics.on(new PayoutFailed("e2", now, "po_2", "m_1", "failed", 5_000, "account_closed"));

        assertThat(meters.get(PaymentMetrics.PAYOUTS)
                        .tag("outcome", "sent")
                        .tag("kind", "instant")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get(PaymentMetrics.PAYOUTS)
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get(PaymentMetrics.PAYOUTS + ".amount")
                        .tag("outcome", "sent")
                        .summary()
                        .totalAmount())
                .isEqualTo(123.45);
    }

    @Test
    void aCheckoutCountsOnlyWhenItsTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.checkoutStarted("order_line", "authorized");
            assertThat(meters.find(PaymentMetrics.CHECKOUTS).counter()).isNull();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(meters.get(PaymentMetrics.CHECKOUTS)
                        .tag("ref_type", "order_line")
                        .tag("status", "authorized")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aScheduledPayoutsDelayIsTimedOnceCommitted() {
        metrics.scheduledPayoutSent(java.time.Duration.ofMinutes(12));
        metrics.scheduledPayoutSent(java.time.Duration.ofMinutes(-3)); // before the payout time: on time

        var delay =
                meters.get(PaymentMetrics.PAYOUT_DELAY).tag("kind", "scheduled").timer();
        assertThat(delay.count()).isEqualTo(2);
        assertThat(delay.totalTime(java.util.concurrent.TimeUnit.MINUTES)).isEqualTo(12);
    }
}
