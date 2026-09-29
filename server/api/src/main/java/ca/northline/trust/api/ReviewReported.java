package ca.northline.trust.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code review.reported} — the business reported a review; it lands in the console trust &amp; safety queue as an open
 * {@code review_report} flag. Externalized to Kafka topic {@code trust.review}, key = review id. Schema:
 * {@code resources/events/trust.review_reported.v1.schema.json}.
 *
 * @param reason {@code fake | offensive | personal_info | wrong_business | other}
 */
@Externalized("trust.review::#{aggregateId()}")
public record ReviewReported(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId, String reason)
        implements DomainEvent {}
