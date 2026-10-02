package ca.northline.worker.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 * The DLQ replay tool (S-26, extended in S-115; docs/runbooks/events.md § DLQ). Reads a {@code <topic>.dlq} from the
 * beginning without joining a consumer group (nothing is committed; the DLQ stays as it is), picks the records a
 * consumer group dead-lettered (header {@code kafka_dlt-original-consumer-group}) that match the {@link Filter} — event
 * ids, event type, when they were dead-lettered, the failure's text — and republishes each to its original topic with
 * its key, value and envelope headers ({@code nl-replayed-from} added, Spring's {@code kafka_*} and
 * {@code retry_topic-*} headers dropped), at most {@code ratePerSecond} records a second.
 *
 * <p>Every group of that topic sees the replayed record; the groups that had processed it find their
 * {@code events.processed_events} claim and skip it, so only the failed group does the work again. Each replayed DLQ
 * record is claimed as {@code dlq-replay / <dlq>:<partition>:<offset>}: a second run doesn't replay it again unless
 * {@code force}. {@code dryRun} lists what would be replayed. A run that replays writes one platform audit entry
 * ({@code events.dlq_replayed}, {@link OperatorAudit}) with the operator, the reason, the filters and the event ids.
 */
@Slf4j
public final class DlqReplay {

    public static final String CLAIM = "dlq-replay";
    public static final String AUDIT_ACTION = "events.dlq_replayed";
    /** Default pace: enough for a backlog of thousands in minutes, gentle on the consumers and their providers. */
    public static final double DEFAULT_RATE = 20;

    private static final Duration POLL = Duration.ofMillis(500);

    private final Consumer<String, byte[]> consumer;
    private final Producer<String, byte[]> producer;
    private final ProcessedEvents processed;
    private final TransactionOperations transactions;
    private final OperatorAudit audit;
    private final Sleeper sleeper;

    public DlqReplay(
            Consumer<String, byte[]> consumer,
            Producer<String, byte[]> producer,
            ProcessedEvents processed,
            TransactionOperations transactions,
            OperatorAudit audit) {
        this(consumer, producer, processed, transactions, audit, Thread::sleep);
    }

    DlqReplay(
            Consumer<String, byte[]> consumer,
            Producer<String, byte[]> producer,
            ProcessedEvents processed,
            TransactionOperations transactions,
            OperatorAudit audit,
            Sleeper sleeper) {
        this.consumer = consumer;
        this.producer = producer;
        this.processed = processed;
        this.transactions = transactions;
        this.audit = audit;
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Which of the group's DLQ records to take; empty = any.
     *
     * @param eventIds these event ids only
     * @param type this event type only ({@code nl-event-type}, e.g. {@code payments.payout_failed})
     * @param since dead-lettered at or after (the DLQ record's timestamp)
     * @param until dead-lettered before
     * @param reasonContains the failure's message contains this text (case-insensitive)
     */
    public record Filter(
            Set<String> eventIds,
            Optional<String> type,
            Optional<Instant> since,
            Optional<Instant> until,
            Optional<String> reasonContains) {
        public static final Filter ANY =
                new Filter(Set.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        public Filter {
            eventIds = Set.copyOf(eventIds);
        }

        boolean matches(String eventId, String eventType, Instant deadLetteredAt, String reason) {
            return (eventIds.isEmpty() || eventIds.contains(eventId))
                    && type.map(eventType::equals).orElse(true)
                    && since.map(s -> !deadLetteredAt.isBefore(s)).orElse(true)
                    && until.map(deadLetteredAt::isBefore).orElse(true)
                    && reasonContains
                            .map(r -> reason.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT)))
                            .orElse(true);
        }
    }

    /**
     * What to replay.
     *
     * @param limit records to replay (or list) in one run at most; already-replayed ones don't count
     * @param ratePerSecond republished records per second at most
     * @param operator who runs it and why — required unless {@code dryRun}
     */
    public record Request(
            String dlqTopic,
            String group,
            Filter filter,
            int limit,
            boolean dryRun,
            boolean force,
            double ratePerSecond,
            Optional<OperatorAudit.Operator> operator) {
        public Request {
            if (!dlqTopic.endsWith(".dlq")) {
                throw new IllegalArgumentException("Not a DLQ topic: " + dlqTopic);
            }
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be ≥ 1");
            }
            if (!(ratePerSecond > 0)) {
                throw new IllegalArgumentException("rate must be > 0 records per second");
            }
            if (!dryRun && operator.isEmpty()) {
                throw new IllegalArgumentException("A replay needs --actor= and --reason= (audit log)");
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
        var interval = (long) Math.ceil(1000 / request.ratePerSecond());
        var lastSend = 0L;
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
            var type = EventHeaders.text(headers, EventHeaders.TYPE).orElse("?");
            var reason = EventHeaders.text(headers, EventHeaders.DLT_EXCEPTION_MESSAGE)
                    .orElse("");
            if (!EventProcessing.belongsTo(request.group(), group)
                    || !request.filter().matches(eventId, type, Instant.ofEpochMilli(record.timestamp()), reason)) {
                continue;
            }
            var position = "%s:%d:%d".formatted(record.topic(), record.partition(), record.offset());
            var original = EventHeaders.text(headers, EventHeaders.DLT_ORIGINAL_TOPIC)
                    .orElseThrow(() -> new IllegalStateException(position + " has no original topic header"));
            Status status;
            if (!request.force() && processed.contains(CLAIM, position)) {
                status = Status.ALREADY_REPLAYED;
            } else if (request.dryRun()) {
                status = Status.WOULD_REPLAY;
            } else {
                lastSend = pace(lastSend, interval);
                republish(record, original, position);
                status = Status.REPLAYED;
            }
            entries.add(new Entry(position, eventId, type, original, reason, status));
        }
        var result = List.copyOf(entries);
        var replayed =
                result.stream().filter(e -> e.status() == Status.REPLAYED).toList();
        request.operator()
                .filter(_ -> !request.dryRun())
                .ifPresent(operator -> audit.record(
                        operator,
                        AUDIT_ACTION,
                        "kafka_topic",
                        request.dlqTopic(),
                        new AuditDetails(
                                request.group(),
                                request.filter(),
                                request.force(),
                                request.ratePerSecond(),
                                replayed.size(),
                                result.size() - replayed.size(),
                                replayed.stream().map(Entry::eventId).limit(200).toList())));
        return result;
    }

    /** What the audit entry keeps (ids and counts only — the payloads stay in Kafka). */
    record AuditDetails(
            String group,
            Filter filter,
            boolean force,
            double ratePerSecond,
            int replayed,
            int alreadyReplayed,
            List<String> eventIds) {}

    /** Waits until {@code interval} ms have passed since the last send; returns the new send time. */
    private long pace(long lastSend, long interval) {
        var wait = lastSend + interval - System.currentTimeMillis();
        if (lastSend > 0 && wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DLQ replay interrupted", e);
            }
        }
        return System.currentTimeMillis();
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
