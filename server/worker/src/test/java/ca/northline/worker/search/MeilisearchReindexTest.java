package ca.northline.worker.search;

import static ca.northline.worker.search.SearchIndexerTest.listing;
import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.MeilisearchIndexAdmin;
import ca.northline.searchindex.MeilisearchLayout;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.worker.events.EnvelopeParser;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * S-71 / S-115 on the {@code meilisearch} provider: a versioned index built from Postgres beside {@code listings_<lang>},
 * events published meanwhile caught up from Kafka, both languages swapped in one Meilisearch task (the versioned names
 * then hold the previous documents and are deleted, or kept for a rollback), and the runbook's partial reindex and
 * rollback through the operator's command.
 */
class MeilisearchReindexTest extends MeilisearchWorkerTest {

    static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    TransactionOperations transactions;

    @Autowired
    DocumentSource source;

    @Autowired
    SearchProjection projection;

    @Autowired
    EnvelopeParser parser;

    final MeilisearchIndexAdmin admin =
            new MeilisearchIndexAdmin(WorkerContainers.meilisearchClient(), MeilisearchLayout.fromClasspath());

    @BeforeAll
    static void producer() {
        WorkerContainers.start();
        producer = new KafkaProducer<>(Events.producerConfig(KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void close() {
        producer.close();
    }

    SearchReindex reindex(KafkaConsumer<String, byte[]> kafka, SearchReindex.Listener listener) {
        return new SearchReindex(
                dataSource,
                jdbc,
                transactions,
                source,
                projection,
                admin,
                kafka,
                parser,
                SearchIndexer.topics(ca.northline.worker.topics.TopicCatalogue.fromClasspath()),
                Clock.systemUTC(),
                listener);
    }

    KafkaConsumer<String, byte[]> consumer() {
        return new KafkaConsumer<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "enable.auto.commit", false),
                new StringDeserializer(),
                new ByteArrayDeserializer());
    }

    @Test
    void rebuildsFromPostgres_catchesUpFromKafka_swapsAtomically_whileLive() throws Exception {
        var fx = new SearchFixtures(jdbc);
        var m = fx.merchant("provider", "trusted", "Meili Reindex Test Co");
        var kept = fx.service(m, "Brake bleed", null, 5000, "live");
        live("catalogue.listing", "catalogue.listing_published", listing(kept, m.id(), "service"));
        // a document the index has but Postgres doesn't (drift): the rebuild drops it
        var ghost = Events.id();
        var meili = WorkerContainers.meilisearchClient();
        var ghostDoc = JsonNodeFactory.instance.arrayNode();
        ghostDoc.addObject()
                .put("id", ghost)
                .put("kind", "service")
                .put("market", "AB")
                .put("merchantId", m.id());
        meili.await(meili.addDocuments("listings_en", ghostDoc));
        // a listing live in Postgres whose event was lost: the rebuild finds it
        var lost = fx.service(m, "Alternator check", null, 9000, "live");
        assertThat(doc("listings_en", lost)).isEmpty();

        var added = new ArrayList<String>();
        var seenDuring = new CopyOnWriteArrayList<String>();
        var phases = new CopyOnWriteArrayList<SearchReindex.Phase>();
        SearchReindex.Result result;
        try (var kafka = consumer()) {
            result = reindex(kafka, (phase, _) -> {
                        phases.add(phase);
                        seenDuring.add(phase + ":"
                                + doc("listings_en", kept)
                                        .map(ListingDocument::name)
                                        .orElse("-"));
                        if (phase == SearchReindex.Phase.BACKFILLED) {
                            // published while the rebuild runs: the live indexer writes the live index, the
                            // catch-up the new one
                            var late = fx.service(m, "Clutch adjustment", null, 7000, "live");
                            added.add(late);
                            live("catalogue.listing", "catalogue.listing_published", listing(late, m.id(), "service"));
                        }
                    })
                    .run(SearchReindex.Options.DEFAULT);
        }

        assertThat(phases)
                .containsExactly(
                        SearchReindex.Phase.LOCKED,
                        SearchReindex.Phase.INDICES_CREATED,
                        SearchReindex.Phase.BACKFILLED,
                        SearchReindex.Phase.CAUGHT_UP,
                        SearchReindex.Phase.SWAPPED,
                        SearchReindex.Phase.FINISHED);
        assertThat(seenDuring).allMatch(s -> s.endsWith(":Brake bleed"));
        assertThat(result.created().get(SearchLanguage.EN)).startsWith("listings_en_v1_");
        assertThat(result.previous().get(SearchLanguage.EN)).containsExactly("listings_en");
        assertThat(result.previousDeleted()).isTrue();
        assertThat(admin.all(SearchLanguage.EN)).isEmpty(); // the previous documents, swapped out, are deleted
        assertThat(result.events()).isPositive();
        assertThat(doc("listings_en", kept)).isPresent();
        assertThat(doc("listings_fr", kept)).isPresent();
        assertThat(doc("listings_en", lost)).isPresent();
        assertThat(doc("listings_en", added.getFirst())).isPresent();
        assertThat(doc("listings_en", ghost)).isEmpty();
        assertThat(meili.settings("listings_en").path("rankingRules").get(0).asString())
                .isEqualTo("sort");
        // the live indexer keeps writing listings_en, which now holds the new documents
        jdbc.sql("update catalogue.services set status = 'hidden' where id = :id")
                .param("id", kept)
                .update();
        live("catalogue.listing", "catalogue.listing_hidden", listing(kept, m.id(), "service"));
        assertThat(doc("listings_en", kept)).isEmpty();
    }

    @Test
    void aFailureBeforeTheSwapChangesNothing() {
        var fx = new SearchFixtures(jdbc);
        var m = fx.merchant("provider", "trusted", "Meili Failure Test Co");
        var service = fx.service(m, "Battery test", null, 3000, "live");
        live("catalogue.listing", "catalogue.listing_published", listing(service, m.id(), "service"));
        var before = admin.count("listings_en");
        try (var kafka = consumer()) {
            assertThatThrownBy(() -> reindex(kafka, (phase, _) -> {
                                if (phase == SearchReindex.Phase.BACKFILLED) {
                                    throw new IllegalStateException("simulated failure");
                                }
                            })
                            .run(SearchReindex.Options.DEFAULT))
                    .hasMessage("simulated failure");
        }
        assertThat(admin.all(SearchLanguage.EN)).isEmpty(); // the half-built index was deleted
        assertThat(admin.all(SearchLanguage.FR)).isEmpty();
        assertThat(admin.count("listings_en")).isEqualTo(before);
        assertThat(doc("listings_en", service)).isPresent();
    }

    /**
     * The S-115 runbook drill on Meilisearch through the operator's command: a partial reindex brings back a lost
     * document; a full reindex with --keep-old, then --rollback, swaps the previous documents back in and fills in what
     * changed meanwhile; with the kept indexes deleted, the next rollback is refused.
     */
    @Test
    void runbookDrill_partialReindex_thenRollbackAfterKeepOld() {
        var fx = new SearchFixtures(jdbc);
        var m = fx.merchant("provider", "trusted", "Meili Drill Test Co");
        var listing = fx.service(m, "Wheel alignment", null, 8000, "live");
        live("catalogue.listing", "catalogue.listing_published", listing(listing, m.id(), "service"));
        var meili = WorkerContainers.meilisearchClient();
        meili.await(meili.deleteDocuments("listings_en", List.of(listing)));
        assertThat(doc("listings_en", listing)).isEmpty(); // drift: the index lost it

        assertThatThrownBy(() -> SearchReindexCommand.partial(commandArgs("--partial")))
                .hasMessageContaining("--merchant");
        assertThat(SearchReindexCommand.partial(commandArgs("--partial", "--merchant=" + m.id())))
                .isEqualTo(1);
        assertThat(doc("listings_en", listing)).isPresent();

        var result = SearchReindexCommand.run(commandArgs("--keep-old"));
        var keptEn = result.created().get(SearchLanguage.EN);
        assertThat(admin.all(SearchLanguage.EN)).containsExactly(keptEn); // the previous documents, kept
        // a listing made live after the swap, without an event: only the live documents get it (from the sweep)
        var later = fx.service(m, "Tire rotation", null, 4000, "live");

        var rolledBack = SearchReindexCommand.rollback(commandArgs("--rollback"));

        assertThat(rolledBack).isEqualTo(result.created());
        assertThat(doc("listings_en", later)).isPresent(); // the rollback's sweep
        assertThat(doc("listings_en", listing)).isPresent();
        // tidy up like the runbook says: the newer documents are under the kept names now
        result.created().values().forEach(admin::delete);
        assertThatThrownBy(() -> SearchReindexCommand.rollback(commandArgs("--rollback")))
                .hasMessageContaining("--keep-old");
    }

    private static String[] commandArgs(String... extra) {
        var args = new ArrayList<>(List.of(
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.datasource.url=" + WorkerContainers.POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + WorkerContainers.POSTGRES.getUsername(),
                "--spring.datasource.password=" + WorkerContainers.POSTGRES.getPassword(),
                "--northline.search.provider=meilisearch",
                "--northline.search.meilisearch.url=" + WorkerContainers.meilisearch(),
                "--northline.search.meilisearch.api-key=" + WorkerContainers.MEILI_KEY));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    /** An event as the api publishes it (on Kafka, for the reindex's catch-up) and as the live indexer applies it. */
    private void live(String topic, String type, String payload) {
        var id = payload.substring(payload.indexOf("\"eventId\":\"") + 11, payload.indexOf("\"eventId\":\"") + 37);
        assertThat(producer.send(Events.record(topic, id, type, 1, payload))).succeedsWithin(Duration.ofSeconds(30));
        consume(topic, type, payload.replace(id, Events.id())); // another delivery id: the consumer dedupes by id
    }
}
