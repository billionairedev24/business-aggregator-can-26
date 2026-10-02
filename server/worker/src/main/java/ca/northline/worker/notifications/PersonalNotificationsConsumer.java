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
 * Consumer group {@code personal-notifications} (S-102, deploy/kafka/topics.yaml): customers' order, delivery, booking
 * and quote updates and couriers' runs, through the S-26 framework. It has <b>no retry topics</b> — 15 more would pass
 * Event Hubs Premium's 100 per processing unit — because nothing it sends throws: a provider outage or throttling is
 * written to {@code messaging.deferred_notifications} and retried by the deferred job (every 5 minutes, 10 attempts).
 * Only an unexpected failure (a bug, the database) goes to the {@code .dlq}, for a replay.
 */
@Component
@RequiredArgsConstructor
class PersonalNotificationsConsumer {

    static final String GROUP = "personal-notifications";

    private final EventProcessing events;
    private final Notifier notifier;

    @RetryableTopic(
            attempts = "1",
            backOff = @BackOff(delay = 10_000, multiplier = 6),
            retryTopicSuffix = ".personal-notifications.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(
            topics = {"orders.order", "fulfilment.delivery", "fulfilment.run", "booking.booking", "booking.quote"},
            groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, notifier::on);
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }
}
