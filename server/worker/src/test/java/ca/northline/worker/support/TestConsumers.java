package ca.northline.worker.support;

import ca.northline.worker.events.EventEnvelope;
import ca.northline.worker.events.EventHeaders;
import ca.northline.worker.events.EventProcessing;
import ca.northline.worker.events.PoisonEventException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;

/**
 * Two consumer groups on {@link WorkerContainers#TEST_TOPIC}, built exactly like production consumers: a scripted one
 * (fails the next N deliveries of an event id) and a bystander that always succeeds. Fast retry delays (100 / 200 ms).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestConsumers {

    @Bean
    Scripted scriptedConsumer(EventProcessing events) {
        return new Scripted(events);
    }

    @Bean
    Bystander bystanderConsumer(EventProcessing events) {
        return new Bystander(events);
    }

    /** Handled deliveries per event id, with the topic each came from. */
    public static class Calls {
        private final Map<String, List<String>> topics = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        private final List<String> deadLettered = new CopyOnWriteArrayList<>();

        public void failNext(String eventId, int times) {
            failures.put(eventId, new AtomicInteger(times));
        }

        public List<String> topicsOf(String eventId) {
            return List.copyOf(topics.getOrDefault(eventId, List.of()));
        }

        public int count(String eventId) {
            return topicsOf(eventId).size();
        }

        public List<String> deadLettered() {
            return List.copyOf(deadLettered);
        }

        void handle(EventEnvelope event) {
            topics.computeIfAbsent(event.id(), _ -> new CopyOnWriteArrayList<>())
                    .add(event.topic());
            var left = failures.get(event.id());
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("scripted failure of " + event.id());
            }
        }
    }

    public static class Scripted {
        public final Calls calls = new Calls();
        private final EventProcessing events;

        Scripted(EventProcessing events) {
            this.events = events;
        }

        @RetryableTopic(
                attempts = "3",
                backOff = @BackOff(delay = 100, multiplier = 2),
                retryTopicSuffix = ".worker-test.retry",
                dltTopicSuffix = ".dlq",
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                autoCreateTopics = "false",
                exclude = PoisonEventException.class,
                traversingCauses = "true")
        @KafkaListener(topics = WorkerContainers.TEST_TOPIC, groupId = WorkerContainers.TEST_GROUP)
        void on(ConsumerRecord<String, byte[]> record) {
            events.process(WorkerContainers.TEST_GROUP, record, calls::handle);
        }

        @DltHandler
        void deadLetter(ConsumerRecord<String, byte[]> record) {
            if (events.deadLettered(WorkerContainers.TEST_GROUP, record)) {
                calls.deadLettered.add(
                        EventHeaders.text(record.headers(), EventHeaders.ID).orElse("?"));
            }
        }
    }

    public static class Bystander {
        public final Calls calls = new Calls();
        private final EventProcessing events;

        Bystander(EventProcessing events) {
            this.events = events;
        }

        @RetryableTopic(
                attempts = "2",
                backOff = @BackOff(delay = 100, multiplier = 2),
                retryTopicSuffix = ".worker-bystander.retry",
                dltTopicSuffix = ".dlq",
                topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
                autoCreateTopics = "false",
                exclude = PoisonEventException.class,
                traversingCauses = "true")
        @KafkaListener(topics = WorkerContainers.TEST_TOPIC, groupId = WorkerContainers.BYSTANDER_GROUP)
        void on(ConsumerRecord<String, byte[]> record) {
            events.process(WorkerContainers.BYSTANDER_GROUP, record, calls::handle);
        }

        @DltHandler
        void deadLetter(ConsumerRecord<String, byte[]> record) {
            if (events.deadLettered(WorkerContainers.BYSTANDER_GROUP, record)) {
                calls.deadLettered.add(
                        EventHeaders.text(record.headers(), EventHeaders.ID).orElse("?"));
            }
        }
    }
}
