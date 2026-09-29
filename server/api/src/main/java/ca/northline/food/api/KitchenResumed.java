package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/** {@code kitchen.resumed} — "Paused · resume" before the auto-resume. Topic {@code food.kitchen}, key = merchant id. */
@Externalized("food.kitchen::#{aggregateId()}")
public record KitchenResumed(String eventId, Instant occurredAt, String aggregateId, String actorId)
        implements DomainEvent {}
