package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.confirmed} — the customer confirmed they received the order (S-78, design 06 "you confirm, shops
 * paid"): goods escrow releases at once instead of 7 days after delivery. Kafka topic {@code orders.order}, key =
 * order id. Ids only (the customer id stays out, as in the partner payloads).
 *
 * @param orderType {@code goods} | {@code food}
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderConfirmed(String eventId, Instant occurredAt, String aggregateId, String orderType)
        implements DomainEvent {}
