package ca.northline.shared;

import ca.northline.platform.EnvelopedEvent;
import java.time.Instant;

/**
 * Marker for all domain events. Name: <aggregate>.<past_tense>.
 * Payloads carry ids, not PII. Schemas: api/src/main/resources/events/*.schema.json
 * {@code eventId()} (ULID, consumer dedupe key) and {@code version()} come from {@link EnvelopedEvent}: the Kafka
 * envelope headers shared with northline-auth.
 */
public interface DomainEvent extends EnvelopedEvent {
    Instant occurredAt();

    String aggregateId();
}
