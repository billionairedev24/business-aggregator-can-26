package ca.northline.shared;

import java.time.Instant;

/**
 * Marker for all domain events. Name: <aggregate>.<past_tense>.
 * Payloads carry ids, not PII. Schemas: api/src/main/resources/events/*.schema.json
 */
public interface DomainEvent {
    String eventId(); // ULID — consumer dedupe key

    Instant occurredAt();

    String aggregateId();

    default int version() {
        return 1;
    }
}
