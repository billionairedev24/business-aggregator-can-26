package ca.northline.worker;

import ca.northline.worker.events.EventEnvelope;
import ca.northline.worker.events.EventProcessing;
import ca.northline.worker.events.PoisonEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Projects catalogue/food/storefront events into Elasticsearch. Retries after 10 s, 60 s and 5 min on
 * {@code <topic>.search-indexer.retry-<n>}, then {@code <topic>.dlq} — the policy of consumer {@code search-indexer}
 * in deploy/kafka/topics.yaml (TopicCatalogueTest keeps them equal; the topics are provisioned, never auto-created).
 * Parsing, schema validation, dedupe and metrics: {@link EventProcessing}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SearchIndexer {

    static final String GROUP = "search-indexer";

    private final EventProcessing events;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 10_000, multiplier = 6, maxDelay = 300_000),
            retryTopicSuffix = ".search-indexer.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(
            topics = {"catalogue.listing", "food.menu", "merchants.storefront"},
            groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, this::index);
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }

    private void index(EventEnvelope event) {
        // TODO(implement, S-42/S-43): map to SearchDocument and upsert into listings_en / listings_fr
        log.debug("search projection not built yet: {} {}", event.type(), event.aggregateId());
    }
}
