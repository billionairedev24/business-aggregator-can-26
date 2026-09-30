package ca.northline.payments.application;

import ca.northline.payments.api.PayoutFailed;
import ca.northline.payments.api.PayoutSent;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * S-111 business metrics of the money flow (dashboard "Northline · payments", docs/runbooks/observability.md):
 *
 * <ul>
 *   <li>{@code northline.checkouts} — escrow holds opened at checkout, by {@code ref_type} (order line, booking deposit…)
 *       and {@code status} (authorized, requires_action, failed…);
 *   <li>{@code northline.payouts} — payouts sent or failed, by {@code kind} / {@code outcome}; the amounts as the
 *       distribution {@code northline.payouts.amount} (CAD, dollars).
 * </ul>
 *
 * Counted only once the transaction commits (a rolled-back checkout never happened). Plain {@code @EventListener}: no
 * outbox row per event, unlike a module listener. Tags never carry ids.
 */
@Component
@RequiredArgsConstructor
class PaymentMetrics {

    static final String CHECKOUTS = "northline.checkouts";
    static final String PAYOUTS = "northline.payouts";

    private final MeterRegistry meters;

    void checkoutStarted(String refType, String status) {
        afterCommit(() -> meters.counter(CHECKOUTS, "ref_type", refType, "status", status)
                .increment());
    }

    @EventListener
    void on(PayoutSent payout) {
        afterCommit(() -> record("sent", payout.kind(), payout.amountCents()));
    }

    @EventListener
    void on(PayoutFailed payout) {
        afterCommit(() -> record(payout.outcome(), "unknown", payout.amountCents()));
    }

    private void record(String outcome, String kind, long amountCents) {
        meters.counter(PAYOUTS, "outcome", outcome, "kind", kind).increment();
        DistributionSummary.builder(PAYOUTS + ".amount")
                .baseUnit("CAD")
                .tag("outcome", outcome)
                .register(meters)
                .record(amountCents / 100.0);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
