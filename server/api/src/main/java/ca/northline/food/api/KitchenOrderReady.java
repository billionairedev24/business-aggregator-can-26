package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.ready} — "Mark ready": the food is bagged and sealed; the courier (or customer) can collect it. The orders
 * module moves the order to {@code ready}. {@code late} = after the promised time (counts against the on-time score).
 * Topic {@code orders.order}, key = order id.
 */
@Externalized("orders.order::#{aggregateId()}")
public record KitchenOrderReady(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId, boolean late)
        implements DomainEvent {}
