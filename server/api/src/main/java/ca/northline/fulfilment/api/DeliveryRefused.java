package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Age-restricted items (2026-10-04): the courier didn't hand the order over at the door — no photo ID, under age, the
 * name doesn't match the customer, nobody of age there, or the recipient was intoxicated. The courier takes it back to
 * the business (a {@code return} stop on the run); the orders module moves the order to {@code returned} and refunds
 * by the rules (docs/runbooks/age-restricted.md § Refunds). In-process only; ids and codes.
 *
 * @param aggregateId the order
 * @param reason {@code no_id | underage | mismatch | nobody_of_age | intoxicated | other}
 * @param checkId the ID check's record ({@code restricted.handoff_checks})
 */
public record DeliveryRefused(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        @Nullable String runId,
        @Nullable String courierId,
        String reason,
        String checkId)
        implements DomainEvent {}
