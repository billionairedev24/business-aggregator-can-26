package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.packed} — a seller packed its lines of an order; the courier scans each bag at pickup. {@code orderState}
 * is {@code ready} once every merchant on the order has packed, otherwise {@code packing}. Externalized to Kafka topic
 * {@code orders.order}, key = order id.
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderPacked(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String actorId,
        int linesPacked,
        String orderState)
        implements DomainEvent {}
