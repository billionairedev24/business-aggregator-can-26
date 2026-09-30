package ca.northline.worker.search;

import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ca.northline.searchindex.IndexLayout;
import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingIndices;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.worker.events.EnvelopeParser;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * S-71 on Kafka 4 + PostGIS + Elasticsearch 9 with the whole worker running (the live indexer keeps writing to the
 * aliases meanwhile): a new versioned index built from Postgres, events published during the rebuild caught up from
 * Kafka, an atomic alias swap, the old index deleted — and searches answered from a complete index at every phase.
 */
class SearchReindexTest extends WorkerIntegrationTest {

    static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    TransactionOperations transactions;

    @Autowired
    ElasticsearchClient es;

    @Autowired
    DocumentSource source;

    @Autowired
    SearchProjection projection;

    @Autowired
    JsonMapper json;

    @Autowired
    EnvelopeParser parser;

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
                new ListingIndices(es),
                IndexLayout.fromClasspath(),
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
        var m = fx.merchant("provider", "trusted", "Reindex Test Co");
        var kept = fx.service(m, "Brake bleed", null, 5000, "live");
        // indexed live, the normal way
        send("catalogue.listing", "catalogue.listing_published", listing(kept, m.id()));
        await().atMost(Duration.ofSeconds(5)).until(() -> doc("listings_en", kept), Optional::isPresent);
        // a document the index has but Postgres doesn't (drift): the rebuild drops it
        var ghost = Events.id();
        es.index(i -> i.index("listings_en")
                .id(ghost)
                .document(Map.of(
                        "id",
                        ghost,
                        "kind",
                        "service",
                        "market",
                        "AB",
                        "merchantId",
                        m.id(),
                        "name",
                        "Ghost listing")));
        // a listing live in Postgres whose event was lost: the rebuild finds it
        var lost = fx.service(m, "Alternator check", null, 9000, "live");
        assertThat(doc("listings_en", lost)).isEmpty();

        var indices = new ListingIndices(es);
        var before = indices.current(SearchLanguage.EN).orElseThrow();
        var added = new ArrayList<String>();
        var seenDuring = new CopyOnWriteArrayList<String>();
        var phases = new CopyOnWriteArrayList<SearchReindex.Phase>();

        SearchReindex.Result result;
        try (var kafka = consumer()) {
            result = reindex(kafka, (phase, created) -> {
                        phases.add(phase);
                        // searches keep working on the live alias throughout
                        seenDuring.add(phase + ":"
                                + doc("listings_en", kept)
                                        .map(ListingDocument::name)
                                        .orElse("-"));
                        if (phase == SearchReindex.Phase.BACKFILLED) {
                            // published while the rebuild runs: the live indexer writes the old index, the catch-up the
                            // new
                            var late = fx.service(m, "Clutch adjustment", null, 7000, "live");
                            added.add(late);
                            send("catalogue.listing", "catalogue.listing_published", listing(late, m.id()));
                            await().atMost(Duration.ofSeconds(5))
                                    .until(() -> doc("listings_en", late), Optional::isPresent);
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
        var after = indices.current(SearchLanguage.EN).orElseThrow();
        assertThat(after).isNotEqualTo(before).isEqualTo(result.created().get(SearchLanguage.EN));
        assertThat(indices.all(SearchLanguage.EN)).containsExactly(after); // the old one is gone
        assertThat(result.previousDeleted()).isTrue();
        assertThat(result.events()).isPositive();
        assertThat(result.merchants()).isPositive();
        assertThat(doc("listings_en", kept)).isPresent();
        assertThat(doc("listings_fr", kept)).isPresent();
        assertThat(doc("listings_en", lost)).isPresent();
        assertThat(doc("listings_en", added.getFirst())).isPresent();
        assertThat(doc("listings_en", ghost)).isEmpty();
        assertThat(indices.meta(after))
                .contains(IndexLayout.fromClasspath()
                        .definition(SearchLanguage.EN)
                        .meta());
        // the live indexer keeps writing the new index through the alias
        jdbc.sql("update catalogue.services set status = 'hidden' where id = :id")
                .param("id", kept)
                .update();
        send("catalogue.listing", "catalogue.listing_hidden", listing(kept, m.id()));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> doc("listings_en", kept).isEmpty());
    }

    @Test
    void oneRunAtATime_andAFailureBeforeTheSwapChangesNothing() throws Exception {
        var indices = new ListingIndices(es);
        var live = indices.current(SearchLanguage.EN).orElseThrow();
        try (var kafka = consumer();
                var held = dataSource.getConnection()) {
            try (var statement = held.prepareStatement("select pg_advisory_lock(hashtextextended(?, 0))")) {
                statement.setString(1, SearchReindex.LOCK);
                statement.execute();
            }
            assertThatThrownBy(() -> reindex(kafka, (_, _) -> {}).run(SearchReindex.Options.DEFAULT))
                    .hasMessageContaining("Another search reindex is running");
            try (var statement = held.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
                statement.setString(1, SearchReindex.LOCK);
                statement.execute();
            }
        }
        try (var kafka = consumer()) {
            assertThatThrownBy(() -> reindex(kafka, (phase, _) -> {
                                if (phase == SearchReindex.Phase.BACKFILLED) {
                                    throw new IllegalStateException("simulated failure");
                                }
                            })
                            .run(SearchReindex.Options.DEFAULT))
                    .hasMessage("simulated failure");
        }
        assertThat(indices.current(SearchLanguage.EN)).contains(live);
        assertThat(indices.all(SearchLanguage.EN)).containsExactly(live); // the half-built index was deleted
        assertThat(indices.all(SearchLanguage.FR)).hasSize(1);
    }

    @Test
    void keepOld_leavesThePreviousIndices() throws Exception {
        var indices = new ListingIndices(es);
        var before = indices.current(SearchLanguage.FR).orElseThrow();
        SearchReindex.Result result;
        try (var kafka = consumer()) {
            result = reindex(kafka, (_, _) -> {}).run(new SearchReindex.Options(true, 50, 3));
        }
        assertThat(result.previousDeleted()).isFalse();
        assertThat(indices.current(SearchLanguage.FR)).contains(result.created().get(SearchLanguage.FR));
        assertThat(indices.all(SearchLanguage.FR))
                .contains(before, result.created().get(SearchLanguage.FR));
        for (var language : SearchLanguage.values()) {
            indices.all(language).stream()
                    .filter(index -> !index.equals(result.created().get(language)))
                    .forEach(indices::delete);
        }
    }

    private void send(String topic, String type, String payload) {
        var id = payload.substring(payload.indexOf("\"eventId\":\"") + 11, payload.indexOf("\"eventId\":\"") + 37);
        producer.send(Events.record(topic, id, type, 1, payload));
        producer.flush();
    }

    private static String listing(String id, String merchantId) {
        return """
                {"eventId":"%s","occurredAt":"2026-09-30T18:00:00Z","aggregateId":"%s","merchantId":"%s","kind":"service"}""".formatted(Events.id(), id, merchantId);
    }

    private Optional<ListingDocument> doc(String index, String id) {
        try {
            var response = es.get(g -> g.index(index).id(id), ListingDocument.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
