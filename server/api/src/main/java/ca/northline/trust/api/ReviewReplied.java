package ca.northline.trust.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code review.replied} — the business replied publicly to a review; the search projection and the customer's
 * notification pick it up. Externalized to Kafka topic {@code trust.review}, key = review id. No reply text in the
 * payload. Schema: {@code resources/events/trust.review_replied.v1.schema.json}.
 */
@Externalized("trust.review::#{aggregateId()}")
public record ReviewReplied(String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId)
        implements DomainEvent {}
