package ca.northline.worker.events;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Kafka header names of the event envelope (written by the api's {@code ca.northline.config.EventHeaders}) and the
 * ones Spring Kafka adds on the way to a {@code .dlq}.
 */
public final class EventHeaders {

    public static final String ID = "nl-event-id";
    public static final String TYPE = "nl-event-type";
    public static final String VERSION = "nl-event-version";
    /** W3C trace context, added by the producer's Kafka observation. */
    public static final String TRACEPARENT = "traceparent";
    /** Set by the DLQ replay tool on the republished record: {@code <dlq topic>:<partition>:<offset>}. */
    public static final String REPLAYED_FROM = "nl-replayed-from";

    // Spring Kafka 4's DeadLetterPublishingRecoverer writes the kafka_original-* / kafka_exception-* names (the
    // kafka_dlt-* ones only for the consumer group).
    public static final String DLT_ORIGINAL_TOPIC = KafkaHeaders.ORIGINAL_TOPIC;
    public static final String DLT_ORIGINAL_GROUP = KafkaHeaders.ORIGINAL_CONSUMER_GROUP;
    public static final String DLT_EXCEPTION_MESSAGE = KafkaHeaders.EXCEPTION_MESSAGE;
    public static final String DLT_EXCEPTION_FQCN = KafkaHeaders.EXCEPTION_FQCN;
    public static final String DLT_EXCEPTION_CAUSE_FQCN = KafkaHeaders.EXCEPTION_CAUSE_FQCN;

    private EventHeaders() {}

    /** The last value of a header as UTF-8 text. */
    public static Optional<String> text(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header == null || header.value() == null
                ? Optional.empty()
                : Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }
}
