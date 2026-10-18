package ca.northline.worker.search;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.MeilisearchDocuments;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The worker with {@code northline.search.provider=meilisearch} against Meilisearch (Testcontainers), one context for
 * every Meilisearch test. Its Kafka listeners stay off: the other worker tests' context shares the
 * {@code search-indexer} group (a second member would take half its partitions), so the tests hand records to the
 * consumer as Kafka would ({@link #consume}).
 */
abstract class MeilisearchWorkerTest extends WorkerIntegrationTest {

    @Autowired
    SearchIndexer indexer;

    @DynamicPropertySource
    static void meilisearch(DynamicPropertyRegistry registry) {
        registry.add("northline.search.provider", () -> "meilisearch");
        registry.add("northline.search.meilisearch.url", WorkerContainers::meilisearch);
        registry.add("northline.search.meilisearch.api-key", () -> WorkerContainers.MEILI_KEY);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.elasticsearch.uris", () -> "http://localhost:9"); // never called: not the engine
    }

    /** What the search-indexer consumer does with a record of {@code topic} (parse, dedupe, refresh, write). */
    void consume(String topic, String type, String json) {
        var id = json.substring(json.indexOf("\"eventId\":\"") + 11, json.indexOf("\"eventId\":\"") + 37);
        indexer.on(Events.consumed(Events.record(topic, id, type, 1, json)));
    }

    static Optional<ListingDocument> doc(String index, String id) {
        return WorkerContainers.meilisearchClient().document(index, id).map(MeilisearchDocuments::fromMeilisearch);
    }
}
