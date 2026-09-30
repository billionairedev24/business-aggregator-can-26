package ca.northline.worker.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs one Kafka record through a consumer (S-26, CLAUDE.md § Events), called from each {@code @KafkaListener}:
 *
 * <ol>
 *   <li>parse the envelope and validate the payload against its schema — anything unparseable is a
 *       {@link PoisonEventException}, which the listener's {@code @RetryableTopic(exclude = …)} sends straight to the
 *       {@code .dlq};
 *   <li>in one transaction: claim {@code (consumer group, event id)} in {@code events.processed_events} and run the
 *       handler. A duplicate delivery (Kafka is at-least-once; the api's outbox may publish twice; a DLQ replay reaches
 *       every group) finds the claim and does nothing. A handler failure rolls the claim back with the handler's own
 *       writes, so the retry runs it again;
 *   <li>log with the event id, type, consumer and trace id in the MDC, and count the outcome.
 * </ol>
 *
 * Handlers that call outside systems (email, SMS) can't be rolled back; they claim per recipient and channel on top
 * (S-27), so a retry after a partial failure sends only what is missing.
 */
@Slf4j
public class EventProcessing {

    public static final String CONSUMED = "northline.events.consumed";
    public static final String DEAD_LETTERED = "northline.events.dead_lettered";

    private final EnvelopeParser parser;
    private final ProcessedEvents processed;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;

    public EventProcessing(
            EnvelopeParser parser,
            ProcessedEvents processed,
            TransactionOperations transactions,
            MeterRegistry meters) {
        this.parser = parser;
        this.processed = processed;
        this.transactions = transactions;
        this.meters = meters;
    }

    public enum Outcome {
        PROCESSED,
        DUPLICATE
    }

    /** Parses, dedupes and handles one record for {@code consumer} (its Kafka consumer group). */
    public Outcome process(String consumer, ConsumerRecord<String, byte[]> record, EventHandler handler) {
        EventEnvelope event;
        try {
            event = parser.parse(record);
        } catch (PoisonEventException e) {
            count(
                    consumer,
                    EventHeaders.text(record.headers(), EventHeaders.TYPE).orElse("unknown"),
                    "poison");
            log.warn("POISON consumer={} {} — straight to the DLQ", consumer, e.getMessage());
            throw e;
        }
        try (var _ = MDC.putCloseable("eventId", event.id());
                var _ = MDC.putCloseable("eventType", event.type());
                var _ = MDC.putCloseable("consumer", consumer);
                var _ = event.traceId() == null ? null : MDC.putCloseable("traceId", event.traceId())) {
            Outcome outcome;
            try {
                outcome = transactions.execute(_ -> {
                    if (!processed.claim(consumer, event.id())) {
                        return Outcome.DUPLICATE;
                    }
                    handler.handle(event);
                    return Outcome.PROCESSED;
                });
            } catch (RuntimeException e) {
                count(consumer, event.type(), "failed");
                log.warn(
                        "FAILED consumer={} event={} type={} topic={}: {} — retried, then {}.dlq",
                        consumer,
                        event.id(),
                        event.type(),
                        record.topic(),
                        e.toString(),
                        mainTopic(record));
                throw e;
            }
            var result = outcome == null ? Outcome.PROCESSED : outcome;
            count(consumer, event.type(), result.name().toLowerCase(Locale.ROOT));
            if (result == Outcome.DUPLICATE) {
                log.info(
                        "DUPLICATE consumer={} event={} type={} — already processed",
                        consumer,
                        event.id(),
                        event.type());
            } else {
                log.debug("processed consumer={} event={} type={}", consumer, event.id(), event.type());
            }
            return result;
        }
    }

    /**
     * Called by each consumer's {@code @DltHandler} for every record of its topics' {@code .dlq} (shared by all
     * groups). Records another group dead-lettered are ignored; this group's are logged at ERROR and counted
     * ({@code northline.events.dead_lettered}) — the alert. The record stays in the DLQ for {@link DlqReplay}.
     */
    public boolean deadLettered(String consumer, ConsumerRecord<String, byte[]> record) {
        var group = EventHeaders.text(record.headers(), EventHeaders.DLT_ORIGINAL_GROUP)
                .orElse("");
        if (!belongsTo(consumer, group)) {
            return false;
        }
        var original = EventHeaders.text(record.headers(), EventHeaders.DLT_ORIGINAL_TOPIC)
                .orElse(record.topic());
        meters.counter(DEAD_LETTERED, "consumer", consumer, "topic", original).increment();
        log.error(
                "DEAD-LETTERED consumer={} event={} type={} topic={} dlq={}-{}@{} reason={} {} — see docs/runbooks/events.md"
                        + " § DLQ",
                consumer,
                EventHeaders.text(record.headers(), EventHeaders.ID).orElse("?"),
                EventHeaders.text(record.headers(), EventHeaders.TYPE).orElse("?"),
                original,
                record.topic(),
                record.partition(),
                record.offset(),
                EventHeaders.text(record.headers(), EventHeaders.DLT_EXCEPTION_FQCN)
                        .orElse("?"),
                EventHeaders.text(record.headers(), EventHeaders.DLT_EXCEPTION_MESSAGE)
                        .orElse(""));
        return true;
    }

    /** A group's retry endpoints run as {@code <group>-retry-<n>}; the DLT header names the one that gave up. */
    static boolean belongsTo(String consumer, String group) {
        return group.equals(consumer) || group.startsWith(consumer + "-");
    }

    private void count(String consumer, String type, String outcome) {
        meters.counter(CONSUMED, "consumer", consumer, "type", type, "outcome", outcome)
                .increment();
    }

    private static String mainTopic(ConsumerRecord<String, byte[]> record) {
        var topic = record.topic();
        var retry = topic.indexOf(".retry-");
        if (retry < 0) {
            return topic;
        }
        var withoutRetry = topic.substring(0, retry);
        return withoutRetry.substring(0, withoutRetry.lastIndexOf('.'));
    }
}
