package ca.northline.worker.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a Kafka record into an {@link EventEnvelope}: envelope headers ({@code nl-event-id|type|version},
 * {@code traceparent}), the JSON payload, and its schema for that type and version. Anything that can't be parsed is
 * a {@link PoisonEventException}: retrying would give the same answer.
 */
@RequiredArgsConstructor
public final class EnvelopeParser {

    private static final Pattern TRACEPARENT = Pattern.compile("[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    private final JsonMapper json;
    private final EventSchemas schemas;

    public EventEnvelope parse(ConsumerRecord<String, byte[]> record) {
        var headers = record.headers();
        var id = EventHeaders.text(headers, EventHeaders.ID).orElseThrow(() -> poison(record, "no nl-event-id header"));
        var type = EventHeaders.text(headers, EventHeaders.TYPE)
                .orElseThrow(() -> poison(record, "no nl-event-type header"));
        var version = EventHeaders.text(headers, EventHeaders.VERSION)
                .filter(v -> v.matches("[1-9][0-9]{0,3}"))
                .map(Integer::parseInt)
                .orElseThrow(() -> poison(record, "no valid nl-event-version header"));
        if (record.value() == null) {
            throw poison(record, "empty value");
        }
        JsonNode data;
        try {
            data = json.readTree(record.value());
        } catch (JacksonException e) {
            throw poison(record, "value is not JSON");
        }
        if (!data.isObject()) {
            throw poison(record, "value is not a JSON object");
        }
        var problems = schemas.validate(type, version, data)
                .orElseThrow(() -> poison(record, "no schema for " + type + " v" + version));
        if (!problems.isEmpty()) {
            throw poison(record, type + " v" + version + " breaks its schema: " + String.join("; ", problems));
        }
        if (!id.equals(data.path("eventId").asString(""))) {
            throw poison(record, "nl-event-id header differs from the payload's eventId");
        }
        Instant occurredAt;
        try {
            occurredAt =
                    OffsetDateTime.parse(data.path("occurredAt").asString("")).toInstant();
        } catch (DateTimeParseException e) {
            throw poison(record, "occurredAt is not a date-time");
        }
        return new EventEnvelope(
                id,
                type,
                version,
                occurredAt,
                data.path("aggregateId").asString(""),
                traceId(EventHeaders.text(headers, EventHeaders.TRACEPARENT).orElse(null)),
                record.topic(),
                data);
    }

    private static @Nullable String traceId(@Nullable String traceparent) {
        if (traceparent == null) {
            return null;
        }
        var m = TRACEPARENT.matcher(traceparent);
        return m.matches() ? m.group(1) : null;
    }

    private static PoisonEventException poison(ConsumerRecord<String, byte[]> record, String reason) {
        return new PoisonEventException(
                "%s-%d@%d: %s".formatted(record.topic(), record.partition(), record.offset(), reason));
    }
}
