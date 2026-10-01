package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code kitchen.auto_resumed} (S-67) — the kitchen caught up after {@link KitchenAutoPaused} (fewer late orders than
 * its threshold, or auto-pause turned off): customers can order again. Topic {@code food.kitchen}, key = merchant id.
 */
@Externalized("food.kitchen::#{aggregateId()}")
public record KitchenAutoResumed(String eventId, Instant occurredAt, String aggregateId) implements DomainEvent {}
