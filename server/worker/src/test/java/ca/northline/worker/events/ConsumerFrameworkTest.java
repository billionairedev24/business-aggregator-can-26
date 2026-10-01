package ca.northline.worker.events;

import static ca.northline.worker.support.WorkerContainers.BYSTANDER_GROUP;
import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static ca.northline.worker.support.WorkerContainers.POSTGRES;
import static ca.northline.worker.support.WorkerContainers.TEST_GROUP;
import static ca.northline.worker.support.WorkerContainers.TEST_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.worker.support.Events;
import ca.northline.worker.support.TestConsumers;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * S-26 end to end on Kafka 4 + PostgreSQL: duplicate delivery processed once, transient failure retried, persistent
 * failure → .dlq with the alert, poison straight to the .dlq, replay to the original topic (only the failed group
 * works again), lag metrics and graceful-shutdown settings.
 */
class ConsumerFrameworkTest extends WorkerIntegrationTest {

    static KafkaProducer<String, byte[]> producer;

    @Autowired
    TestConsumers.Scripted scripted;

    @Autowired
    TestConsumers.Bystander bystander;

    @Autowired
    EventProcessing processing;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @BeforeAll
    static void producer() {
        WorkerContainers.start();
        producer = new KafkaProducer<>(Events.producerConfig(KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void close() {
        producer.close();
    }

    @Test
    void duplicateDeliveryIsProcessedOncePerGroup() throws Exception {
        var id = Events.id();
        send(id);
        send(id); // the api's outbox or Kafka redelivers
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(duplicates(TEST_GROUP)).isGreaterThanOrEqualTo(1);
            assertThat(scripted.calls.count(id)).isEqualTo(1);
            assertThat(bystander.calls.count(id)).isEqualTo(1);
        });
        assertThat(claims(TEST_GROUP, id)).isEqualTo(1);
        assertThat(claims(BYSTANDER_GROUP, id)).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesRunTheHandlerOnce() throws Exception {
        var id = Events.id();
        var record = Events.consumed(
                Events.record(TEST_TOPIC, id, "payments.payout_failed", 1, Events.payoutFailed(id, "m_1")));
        var handled = new AtomicInteger();
        var outcomes = new CopyOnWriteArrayList<EventProcessing.Outcome>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var i = 0; i < 4; i++) {
                threads.execute(() -> outcomes.add(processing.process("concurrency-test", record, _ -> {
                    handled.incrementAndGet();
                    sleep(300);
                })));
            }
        }
        assertThat(handled).hasValue(1);
        assertThat(outcomes)
                .containsExactlyInAnyOrder(
                        EventProcessing.Outcome.PROCESSED,
                        EventProcessing.Outcome.DUPLICATE,
                        EventProcessing.Outcome.DUPLICATE,
                        EventProcessing.Outcome.DUPLICATE);
    }

    @Test
    void transientFailureIsRetriedOnTheGroupsRetryTopicThenProcessed() throws Exception {
        var id = Events.id();
        scripted.calls.failNext(id, 1);
        send(id);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(claims(TEST_GROUP, id)).isEqualTo(1));
        assertThat(scripted.calls.topicsOf(id)).containsExactly(TEST_TOPIC, TEST_TOPIC + ".worker-test.retry-0");
        assertThat(bystander.calls.count(id)).isEqualTo(1); // the other group never saw the retry
        assertThat(scripted.calls.deadLettered()).doesNotContain(id);
    }

    @Test
    void persistentFailureEndsInTheDlqWithTheAlertAndReplayRedoesOnlyThatGroup() throws Exception {
        var id = Events.id();
        var alerts = deadLetterCount(TEST_GROUP);
        scripted.calls.failNext(id, 3);
        send(id);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(scripted.calls.deadLettered()).contains(id));
        assertThat(scripted.calls.topicsOf(id))
                .containsExactly(TEST_TOPIC, TEST_TOPIC + ".worker-test.retry-0", TEST_TOPIC + ".worker-test.retry-1");
        assertThat(claims(TEST_GROUP, id)).isZero(); // rolled back with the failed handler
        assertThat(deadLetterCount(TEST_GROUP)).isEqualTo(alerts + 1);
        assertThat(bystander.calls.deadLettered()).doesNotContain(id); // another group's DLQ record: ignored
        var dead = dlqRecord(id);
        assertThat(EventHeaders.text(dead.headers(), EventHeaders.DLT_ORIGINAL_TOPIC))
                .hasValue(TEST_TOPIC);
        assertThat(EventHeaders.text(dead.headers(), EventHeaders.TYPE)).hasValue("payments.payout_failed");
        await().atMost(Duration.ofSeconds(10)).until(() -> bystander.calls.count(id) == 1);

        // Replay: list first (dry run), then replay; the failure is fixed now.
        var args = replayArgs("--event=" + id);
        var listed = DlqReplayCommand.run(concat("list", args));
        assertThat(listed).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(DlqReplay.Status.WOULD_REPLAY);
            assertThat(e.originalTopic()).isEqualTo(TEST_TOPIC);
            assertThat(e.reason()).contains("scripted failure");
        });
        assertThat(DlqReplayCommand.run(concat("replay", args)))
                .singleElement()
                .extracting(DlqReplay.Entry::status)
                .isEqualTo(DlqReplay.Status.REPLAYED);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(claims(TEST_GROUP, id)).isEqualTo(1));
        assertThat(scripted.calls.count(id)).isEqualTo(4);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(bystander.calls.count(id)).isEqualTo(1)); // deduped, not redone

        assertThat(DlqReplayCommand.run(concat("replay", args)))
                .singleElement()
                .extracting(DlqReplay.Entry::status)
                .isEqualTo(DlqReplay.Status.ALREADY_REPLAYED);
    }

    @Test
    void poisonGoesStraightToTheDlqWithoutRetries() throws Exception {
        var id = Events.id();
        var poisons = count(EventProcessing.CONSUMED, TEST_GROUP, "poison");
        var broken = Events.payoutFailed(id, "m_1").replace("\"failed\"", "\"exploded\"");
        producer.send(Events.record(TEST_TOPIC, id, "payments.payout_failed", 1, broken))
                .get();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(scripted.calls.deadLettered()).contains(id));
        assertThat(scripted.calls.count(id)).isZero();
        assertThat(count(EventProcessing.CONSUMED, TEST_GROUP, "poison")).isEqualTo(poisons + 1); // no retry attempts
        assertThat(EventHeaders.text(dlqRecord(id).headers(), EventHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .hasValue(PoisonEventException.class.getName());
    }

    @Test
    void consumerLagIsMeasuredAndShutdownWaitsForTheRecordInHand() {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(meters.getMeters())
                        .anyMatch(m -> m.getId().getName().equals("kafka.consumer.fetch.manager.records.lag.max")));
        var containers = new ArrayList<MessageListenerContainer>(listeners.getAllListenerContainers());
        assertThat(containers).isNotEmpty();
        assertThat(containers)
                .allSatisfy(c -> assertThat(c.getContainerProperties().getShutdownTimeout())
                        .isEqualTo(20_000L));
    }

    private void send(String id) throws Exception {
        producer.send(Events.record(TEST_TOPIC, id, "payments.payout_failed", 1, Events.payoutFailed(id, "m_1")))
                .get();
    }

    private int claims(String consumer, String id) {
        return jdbc.sql("select count(*) from events.processed_events where consumer = :c and event_id = :id")
                .param("c", consumer)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private double duplicates(String consumer) {
        return count(EventProcessing.CONSUMED, consumer, "duplicate");
    }

    private double count(String name, String consumer, String outcome) {
        return meters.find(name).tag("consumer", consumer).tag("outcome", outcome).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private double deadLetterCount(String consumer) {
        return meters.find(EventProcessing.DEAD_LETTERED).tag("consumer", consumer).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private static ConsumerRecord<String, byte[]> dlqRecord(String id) {
        try (var consumer = new KafkaConsumer<>(
                Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringDeserializer(),
                new ByteArrayDeserializer())) {
            var partitions = consumer.partitionsFor(TEST_TOPIC + ".dlq").stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(300))) {
                    if (EventHeaders.text(record.headers(), EventHeaders.ID)
                            .filter(id::equals)
                            .isPresent()) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError(id + " is not in " + TEST_TOPIC + ".dlq");
    }

    private static List<String> replayArgs(String... extra) {
        var args = new ArrayList<>(List.of(
                "--topic=" + TEST_TOPIC + ".dlq",
                "--group=" + TEST_GROUP,
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()));
        args.addAll(List.of(extra));
        return args;
    }

    private static String[] concat(String command, List<String> args) {
        var all = new ArrayList<String>();
        all.add(command);
        all.addAll(args);
        return all.toArray(String[]::new);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
