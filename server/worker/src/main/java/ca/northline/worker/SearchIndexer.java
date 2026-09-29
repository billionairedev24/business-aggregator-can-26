package ca.northline.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

/** Projects catalogue/food/storefront events into Elasticsearch. Idempotent via processed_events; DLQ after 5 attempts. */
@Component
@RequiredArgsConstructor
class SearchIndexer {
    private final ProcessedEvents processed;

    @RetryableTopic(attempts = "5", backOff = @BackOff(delay = 1000, multiplier = 3), dltTopicSuffix = ".dlq")
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
