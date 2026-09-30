package ca.northline.worker.notifications;

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
 * Consumer group {@code notifications} (deploy/kafka/topics.yaml): the money topics, through the S-26 framework
 * (schema validation, dedupe per event, retries 10 s / 60 s / 5 min on its own retry topics, then {@code .dlq}). The
 * back-off is a property only so tests can shorten it; the catalogue test reads the defaults.
 */
@Component
@RequiredArgsConstructor
class NotificationsConsumer {

    static final String GROUP = "notifications";

    private final EventProcessing events;
    private final Notifier notifier;

    @RetryableTopic(
            attempts = "4",
            backOff =
                    @BackOff(
                            delayString = "${northline.notifications.retry.delay:10000}",
                            multiplierString = "${northline.notifications.retry.multiplier:6}",
                            maxDelayString = "${northline.notifications.retry.max-delay:300000}"),
            retryTopicSuffix = ".notifications.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(
            topics = {"payments.payout", "payments.payout_account", "payments.dispute", "payments.refund"},
            groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, notifier::on);
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }
}
