package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * A refund gave back its share of what points paid (mobile gaps part 2): the promotions module returns those points to
 * the customer's wallet. In-process only; ids and amounts.
 *
 * @param aggregateId the refund ({@code payments.refunds.id}) — the give-back's idempotency key
 * @param escrowRefType / escrowRefId the escrow's reference ({@code order_line}, {@code food_order}, {@code booking})
 * @param cents the points' share in cents
 */
public record PointsReturned(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String customerId,
        String escrowRefType,
        String escrowRefId,
        long cents)
        implements DomainEvent {}
