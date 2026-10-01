package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.delivered} — the order reached the customer (S-78): the courier's drop-off with proof
 * ({@code fulfilment}'s {@code delivery.completed}). For goods this starts each line's 7-day escrow release window
 * (CLAUDE.md) and captures the delivery fee; food was already released at hand-off. Kafka topic {@code orders.order},
 * key = order id. Ids only.
 *
 * @param orderType {@code goods} | {@code food}
 * @param proof {@code photo} | {@code signature} | {@code pin}
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderDelivered(String eventId, Instant occurredAt, String aggregateId, String orderType, String proof)
        implements DomainEvent {}
