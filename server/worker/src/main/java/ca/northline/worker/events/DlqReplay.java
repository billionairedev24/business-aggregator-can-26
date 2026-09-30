package ca.northline.worker.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The DLQ replay tool (S-26; docs/runbooks/events.md § DLQ). Reads a {@code <topic>.dlq} from the beginning without
 * joining a consumer group (nothing is committed; the DLQ stays as it is), picks the records a consumer group
 * dead-lettered (header {@code kafka_dlt-original-consumer-group}), optionally one event id, and republishes each to
 * its original topic with its key, value and envelope headers ({@code nl-replayed-from} added, Spring's
 * {@code kafka_*} and {@code retry_topic-*} headers dropped).
 *
 * <p>Every group of that topic sees the replayed record; the groups that had processed it find their
 * {@code events.processed_events} claim and skip it, so only the failed group does the work again. Each replayed DLQ
 * record is claimed as {@code dlq-replay / <dlq>:<partition>:<offset>}: a second run doesn't replay it again unless
 * {@code force}. {@code dryRun} lists what would be replayed.
 */
@Slf4j
public final class DlqReplay {

    public static final String CLAIM = "dlq-replay";

    private static final Duration POLL = Duration.ofMillis(500);

    private final Consumer<String, byte[]> consumer;
    private final Producer<String, byte[]> producer;
    private final ProcessedEvents processed;
    private final TransactionOperations transactions;

    public DlqReplay(
            Consumer<String, byte[]> consumer,
            Producer<String, byte[]> producer,
            ProcessedEvents processed,
            TransactionOperations transactions) {
        this.consumer = consumer;
        this.producer = producer;
        this.processed = processed;
        this.transactions = transactions;
    }

    /** What to replay. */
    public record Request(
            String dlqTopic, String group, Optional<String> eventId, int limit, boolean dryRun, boolean force) {
        public Request {
            if (!dlqTopic.endsWith(".dlq")) {
                throw new IllegalArgumentException("Not a DLQ topic: " + dlqTopic);
            }
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be ≥ 1");
            }
        }
    }

    /** One DLQ record of the group and what happened to it. */
    public record Entry(
            String dlqPosition, String eventId, String type, String originalTopic, String reason, Status status) {}

    public enum Status {
        REPLAYED,
        WOULD_REPLAY,
        ALREADY_REPLAYED
    }

    public List<Entry> run(Request request) {
        var entries = new ArrayList<Entry>();
        for (var record : read(request.dlqTopic())) {
            if (entries.stream()
                            .filter(e -> e.status() != Status.ALREADY_REPLAYED)
                            .count()
                    >= request.limit()) {
                break;
            }
            var headers = record.headers();
            var group =
                    EventHeaders.text(headers, EventHeaders.DLT_ORIGINAL_GROUP).orElse("");
            var eventId = EventHeaders.text(headers, EventHeaders.ID).orElse("?");
            if (!EventProcessing.belongsTo(request.group(), group)
                    || request.eventId().filter(id -> !id.equals(eventId)).isPresent()) {
                continue;
            }
            var position = "%s:%d:%d".formatted(record.topic(), record.partition(), record.offset());
            var original = EventHeaders.text(headers, EventHeaders.DLT_ORIGINAL_TOPIC)
                    .orElseThrow(() -> new IllegalStateException(position + " has no original topic header"));
            var type = EventHeaders.text(headers, EventHeaders.TYPE).orElse("?");
            var reason = EventHeaders.text(headers, EventHeaders.DLT_EXCEPTION_MESSAGE)
                    .orElse("");
            Status status;
            if (!request.force() && processed.contains(CLAIM, position)) {
                status = Status.ALREADY_REPLAYED;
            } else if (request.dryRun()) {
                status = Status.WOULD_REPLAY;
            } else {
                republish(record, original, position);
                status = Status.REPLAYED;
            }
            entries.add(new Entry(position, eventId, type, original, reason, status));
        }
        return List.copyOf(entries);
    }

    private void republish(ConsumerRecord<String, byte[]> record, String topic, String position) {
        var headers = new RecordHeaders();
        record.headers().forEach(h -> {
            if (!h.key().startsWith("kafka_") && !h.key().startsWith("retry_topic-")) {
                headers.add(h);
            }
        });
        headers.add(EventHeaders.REPLAYED_FROM, position.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(new ProducerRecord<>(topic, null, record.key(), record.value(), headers))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not republish " + position + " to " + topic, e);
        }
        // Claimed after the send: a crash in between replays it once more, which the consumers' dedupe absorbs.
        transactions.executeWithoutResult(_ -> processed.claim(CLAIM, position));
        log.info("REPLAYED {} → {}", position, topic);
    }

    /** Every record of the DLQ, from the beginning up to its end at the time of the call. */
    private List<ConsumerRecord<String, byte[]>> read(String topic) {
        var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("Topic " + topic + " does not exist");
        }
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        var end = consumer.endOffsets(partitions);
        var records = new ArrayList<ConsumerRecord<String, byte[]>>();
        while (partitions.stream().anyMatch(p -> consumer.position(p) < end.getOrDefault(p, 0L))) {
            consumer.poll(POLL).forEach(records::add);
        }
        records.removeIf(r -> r.offset() >= end.getOrDefault(new TopicPartition(r.topic(), r.partition()), 0L));
        return records;
    }
}
