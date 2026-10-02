package ca.northline.worker.notifications;

import ca.northline.worker.events.OperatorAudit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The deferred notifications' replay path (S-115; docs/runbooks/events.md § Deferred notifications): lists the rows of
 * {@code messaging.deferred_notifications} the worker gave up on (dead) and requeues them — fresh attempts, due at
 * {@code dueAt} — so the every-minute job sends them, after re-reading the person and their matrix like any deferred
 * row. Sending is the job's; this only moves rows, in one transaction, with one platform audit entry
 * ({@code notifications.deferred_requeued}). Dedupe still holds: a delivery that did go out is claimed in
 * {@code events.processed_events} and isn't sent twice.
 */
@Slf4j
public final class DeferredRequeue {

    public static final String AUDIT_ACTION = "notifications.deferred_requeued";

    private final DeferredNotifications deferred;
    private final TransactionOperations transactions;
    private final OperatorAudit audit;

    public DeferredRequeue(DeferredNotifications deferred, TransactionOperations transactions, OperatorAudit audit) {
        this.deferred = deferred;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * @param dueAt when the job may send them (now, or the morning after a night-time requeue: requeued rows are not
     *     held for quiet hours again)
     * @param operator required unless {@code dryRun}
     */
    public record Request(
            DeferredNotifications.DeadFilter filter,
            int limit,
            boolean dryRun,
            Instant dueAt,
            Optional<OperatorAudit.Operator> operator) {
        public Request {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be ≥ 1");
            }
            if (!dryRun && operator.isEmpty()) {
                throw new IllegalArgumentException("A requeue needs --actor= and --reason= (audit log)");
            }
        }
    }

    public record Result(List<DeferredNotifications.Dead> rows, int requeued) {}

    record AuditDetails(DeferredNotifications.DeadFilter filter, Instant dueAt, int requeued, List<String> eventIds) {}

    public Result run(Request request) {
        var result = transactions.execute(_ -> {
            var rows = deferred.dead(request.filter(), request.limit());
            if (request.dryRun()) {
                return new Result(rows, 0);
            }
            var requeued = deferred.requeue(
                    rows.stream().map(DeferredNotifications.Dead::id).toList(), request.dueAt());
            request.operator()
                    .ifPresent(operator -> audit.record(
                            operator,
                            AUDIT_ACTION,
                            "table",
                            "messaging.deferred_notifications",
                            new AuditDetails(
                                    request.filter(),
                                    request.dueAt(),
                                    requeued,
                                    rows.stream()
                                            .map(DeferredNotifications.Dead::eventId)
                                            .distinct()
                                            .limit(200)
                                            .toList())));
            return new Result(rows, requeued);
        });
        if (result == null) {
            throw new IllegalStateException("Deferred requeue: no result");
        }
        return result;
    }
}
