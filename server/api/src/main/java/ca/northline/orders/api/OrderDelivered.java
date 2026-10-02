package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.delivered} — the order reached the customer (S-78): the courier's drop-off with proof
 * ({@code fulfilment}'s {@code delivery.completed}). For goods this starts each line's 7-day escrow release window
 * (CLAUDE.md) and captures the delivery fee; food was already released at hand-off. Kafka topic {@code orders.order},
 * key = order id. Ids only.
 *
 * @param orderType {@code goods} | {@code food}
 * @param proof {@code photo} | {@code signature} | {@code pin}
 * @param merchantIds the businesses with lines on the order (one for food, one or more for a pooled goods order),
 *     so each gets its partner webhook (S-33); added to v1 as an optional field, absent from events published before
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderDelivered(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String orderType,
        String proof,
        List<String> merchantIds)
        implements DomainEvent {

    public OrderDelivered {
        merchantIds = List.copyOf(merchantIds);
    }
}
