package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code menu.published} — a menu went live (Menu builder). Search re-indexes its live items. Topic {@code food.menu},
 * key = menu id.
 */
@Externalized("food.menu::#{aggregateId()}")
public record MenuPublished(String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId)
        implements DomainEvent {}
