package ca.northline.food.application;

import ca.northline.food.domain.KitchenTicket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * S-111 kitchen display (KDS) latency, from the ticket's own timestamps, counted once the step commits (dashboard
 * "Northline · kitchens", docs/runbooks/observability.md):
 *
 * <ul>
 *   <li>{@code northline.kds.promised} — minutes promised at "Accept · start cooking";
 *   <li>{@code northline.kds.prep} — accepted → "Mark ready", tagged {@code late} (after the promised time);
 *   <li>{@code northline.kds.handoff_wait} — ready → handed to the courier / customer, tagged {@code mode}.
 * </ul>
 *
 * No merchant or order id in the tags (cardinality; the traces carry them).
 */
@Component
@RequiredArgsConstructor
class KitchenMetrics {

    static final String PROMISED = "northline.kds.promised";
    static final String PREP = "northline.kds.prep";
    static final String HANDOFF_WAIT = "northline.kds.handoff_wait";

    private final MeterRegistry meters;

    void accepted(KitchenTicket ticket) {
        var promised = ticket.getPrepMin();
        if (promised != null) {
            afterCommit(() -> meters.summary(PROMISED).record(promised));
        }
    }

    void ready(KitchenTicket ticket) {
        var readyAt = ticket.getReadyAt();
        var readyBy = ticket.getReadyBy();
        var late = readyAt != null && readyBy != null && readyAt.isAfter(readyBy);
        between(ticket.getAcceptedAt(), readyAt)
                .ifPresent(d -> afterCommit(
                        () -> timer(PREP, "late", Boolean.toString(late)).record(d)));
    }

    void handedOff(KitchenTicket ticket) {
        between(ticket.getReadyAt(), ticket.getHandedOffAt())
                .ifPresent(d -> afterCommit(() ->
                        timer(HANDOFF_WAIT, "mode", ticket.getFulfilmentMode()).record(d)));
    }

    private Timer timer(String name, String tag, String value) {
        return Timer.builder(name).tag(tag, value).register(meters);
    }

    private static Optional<Duration> between(@Nullable Instant from, @Nullable Instant to) {
        return from == null || to == null || to.isBefore(from)
                ? Optional.empty()
                : Optional.of(Duration.between(from, to));
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
