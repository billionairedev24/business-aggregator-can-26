package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code kitchen.paused} — "Pause new orders": customers see "Not accepting orders right now" until {@code pausedUntil}
 * (auto-resume); scheduled orders still come in. Search drops the kitchen from "open now". Topic {@code food.kitchen},
 * key = merchant id.
 */
@Externalized("food.kitchen::#{aggregateId()}")
public record KitchenPaused(String eventId, Instant occurredAt, String aggregateId, String actorId, Instant pausedUntil)
        implements DomainEvent {}
