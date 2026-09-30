package ca.northline.worker.webhooks;

import ca.northline.worker.events.EventProcessing;
import ca.northline.worker.events.PoisonEventException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code webhooks} (deploy/kafka/topics.yaml): the topics whose events partners can subscribe to,
 * through the S-26 framework (schema validation, dedupe, retries 10 s / 60 s / 5 min, then {@code .dlq}). It only
 * queues deliveries ({@link WebhookFanOut}) — no HTTP happens on a Kafka partition.
 */
@Component
@RequiredArgsConstructor
class WebhooksConsumer {

    static final String GROUP = "webhooks";

    private final EventProcessing events;
    private final WebhookFanOut fanOut;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 10_000, multiplier = 6, maxDelay = 300_000),
            retryTopicSuffix = ".webhooks.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(
            topics = {"booking.booking", "payments.escrow", "payments.refund", "orders.order"},
            groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, fanOut::on);
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }
}
