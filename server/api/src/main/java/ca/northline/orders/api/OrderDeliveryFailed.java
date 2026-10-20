package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.delivery_failed} (S-151) — the courier couldn't hand the order over; it is now
 * {@code delivery_failed} and the customer is asked to choose re-delivery on the next run or a refund by
 * {@code decideBy} (after which it is refunded). Topic {@code orders.order}, key = order id. Ids and codes only.
 *
 * @param reason {@code absent} | {@code unsafe} | {@code refused} | {@code access} | {@code other}
 * @param feeCents what a refund keeps of the delivery fee (configuration; 0 by default)
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderDeliveryFailed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String reason,
        Instant decideBy,
        long feeCents,
        int attempt)
        implements DomainEvent {}
