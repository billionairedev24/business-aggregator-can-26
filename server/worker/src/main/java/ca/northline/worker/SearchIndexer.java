package ca.northline.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Projects catalogue/food/storefront events into Elasticsearch. Idempotent via processed_events. Retries after 10 s,
 * 60 s and 5 min on {@code <topic>.search-indexer.retry-<n>}, then {@code <topic>.dlq} — the policy of consumer
 * {@code search-indexer} in deploy/kafka/topics.yaml (TopicCatalogueTest keeps them equal; the topics are provisioned,
 * never auto-created).
 */
@Component
@RequiredArgsConstructor
class SearchIndexer {
    private final ProcessedEvents processed;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 10_000, multiplier = 6, maxDelay = 300_000),
            retryTopicSuffix = ".search-indexer.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false")
    @KafkaListener(
            topics = {"catalogue.listing", "food.menu", "merchants.storefront"},
            groupId = "search-indexer")
    void on(EventEnvelope e) {
        if (!processed.markIfNew("search-indexer", e.id())) {
            return;
        }
        // TODO(implement): map to SearchDocument and upsert into listings_en / listings_fr
    }
}
