package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code kitchen.auto_paused} (S-67) — the Studio's "Auto-pause if late orders ≥ N": {@code lateOrders} accepted
 * orders are past their ready-by time, at least {@code threshold}, so customers can't order until the kitchen catches
 * up ({@link KitchenAutoResumed}). Scheduled orders still come in. Topic {@code food.kitchen}, key = merchant id.
 */
@Externalized("food.kitchen::#{aggregateId()}")
public record KitchenAutoPaused(
        String eventId, Instant occurredAt, String aggregateId, int lateOrders, int threshold)
        implements DomainEvent {}
