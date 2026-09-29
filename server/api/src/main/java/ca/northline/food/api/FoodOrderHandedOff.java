package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.handed_off} — the sealed bag went to the courier ({@code fulfilmentMode = delivery}; order →
 * {@code picked_up}) or to the customer at the counter ({@code pickup}; order → {@code delivered}). Food escrow for this
 * merchant's share releases on this event ("food on handoff", CLAUDE.md) — payments consumes it. Topic
 * {@code orders.order}, key = order id. No PII.
 *
 * @param fulfilmentMode delivery | pickup
 */
@Externalized("orders.order::#{aggregateId()}")
public record FoodOrderHandedOff(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String actorId,
        String fulfilmentMode)
        implements DomainEvent {}
