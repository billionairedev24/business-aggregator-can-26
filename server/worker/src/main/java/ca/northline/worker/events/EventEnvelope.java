package ca.northline.worker.events;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One domain event as a consumer sees it (ARCHITECTURE.md § Event management: id, type, version, occurredAt,
 * aggregate, traceId, data). {@code data} is the whole payload, already validated against
 * {@code events/<type>.v<version>.schema.json}; it carries ids only, never personal data.
 *
 * @param topic the topic the record was read from (a retry topic when retried)
 * @param traceId the producer's trace id from {@code traceparent}, when there was one
 */
public record EventEnvelope(
        String id,
        String type,
        int version,
        Instant occurredAt,
        String aggregateId,
        @Nullable String traceId,
        String topic,
        JsonNode data) {

    /** A text field of the payload; the schema guarantees the required ones. */
    public String text(String field) {
        var value = data.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(type + " has no " + field);
        }
        return value.asString();
    }

    public @Nullable String optionalText(String field) {
        var value = data.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
