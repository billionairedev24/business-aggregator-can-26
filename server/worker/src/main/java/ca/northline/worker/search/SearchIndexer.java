package ca.northline.worker.search;

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
 * Projects catalogue, food, merchant, storefront, trust and availability events into Elasticsearch (S-43): each event
 * names a scope — a listing, a dish or a whole merchant — which {@link SearchProjection} re-reads from Postgres and
 * writes to {@code listings_en} and {@code listings_fr} (visible documents indexed, the rest deleted, versioned).
 * Retries after 10 s, 60 s and 5 min on
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
    private final SearchProjection projection;

    @RetryableTopic(
            attempts = "4",
            // 10 s, 60 s, 5 min; tests shorten them (the topic suffixes don't depend on the delays)
            backOff =
                    @BackOff(
                            delayString = "${northline.search.retry.delay:10000}",
                            multiplierString = "${northline.search.retry.multiplier:6}",
                            maxDelayString = "${northline.search.retry.max-delay:300000}"),
            retryTopicSuffix = ".search-indexer.retry",
            dltTopicSuffix = ".dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(
            topics = {
                "catalogue.listing",
                "food.menu",
                "food.kitchen",
                "merchants.merchant",
                "merchants.storefront",
                "trust.review",
                "availability.availability"
            },
            groupId = GROUP)
    void on(ConsumerRecord<String, byte[]> record) {
        events.process(GROUP, record, this::index);
    }

    @DltHandler
    void deadLetter(ConsumerRecord<String, byte[]> record) {
        events.deadLettered(GROUP, record);
    }

    private void index(EventEnvelope event) {
        var scope = Scope.of(event);
        var outcome = projection.refresh(scope, SearchProjection.Targets.LIVE);
        log.debug(
                "search {} → {} indexed, {} deleted, {} already newer (version {})",
                scope,
                outcome.indexed(),
                outcome.deleted(),
                outcome.stale(),
                outcome.version());
    }
}
