package ca.northline.config;

import ca.northline.shared.DomainEvent;
import ca.northline.shared.EventType;
import java.util.Locale;
import java.util.Map;
import org.springframework.modulith.events.Externalized;

/**
 * The event envelope on Kafka (ARCHITECTURE.md § Event management, S-26): the record value is the event's own JSON
 * (ids only, validated against {@code events/<type>.v<version>.schema.json}), the record key is the aggregate id, and
 * the envelope fields that aren't in the payload travel as headers. {@code traceparent} (W3C) is added by the Kafka
 * template's observation. The worker's {@code EnvelopeParser} reads the same names.
 */
public final class EventHeaders {

    public static final String ID = "nl-event-id";
    public static final String TYPE = "nl-event-type";
    public static final String VERSION = "nl-event-version";

    private EventHeaders() {}

    /** Kafka headers of an externalized event. */
    public static Map<String, Object> of(DomainEvent event) {
        return Map.of(ID, event.eventId(), TYPE, type(event.getClass()), VERSION, Integer.toString(event.version()));
    }

    /**
     * {@code @EventType} when present, else {@code <module of the @Externalized topic>.<snake_case record name>}:
     * {@code PayoutFailed} on {@code payments.payout} → {@code payments.payout_failed}.
     */
    public static String type(Class<?> eventType) {
        var explicit = eventType.getAnnotation(EventType.class);
        if (explicit != null) {
            return explicit.value();
        }
        var externalized = eventType.getAnnotation(Externalized.class);
        if (externalized == null) {
            throw new IllegalArgumentException(eventType.getName() + " is not @Externalized");
        }
        var target = externalized.value().isBlank() ? externalized.target() : externalized.value();
        var module = target.substring(0, target.indexOf('.'));
        return module + "." + snake(eventType.getSimpleName());
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
