package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.searchindex.IndexBootstrap.Finding;
import ca.northline.searchindex.IndexBootstrap.Mode;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The bootstrap against a real Elasticsearch 9 (the compose image): create, idempotence, the analyzers of each
 * language, synonyms reloaded without a reindex, additive mappings in place, "reindex required" for the rest — one
 * node, the steps in order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndexBootstrapTest {

    static final ElasticsearchContainer ES = new ElasticsearchContainer(DockerImageName.parse("elasticsearch:9.1.3")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(java.time.Duration.ofMinutes(4)); // slow under a full parallel build

    static ElasticsearchClient es;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T18:00:00Z"), ZoneOffset.UTC);

    @TempDir
    static Path changed;

    final IndexLayout layout = IndexLayout.fromClasspath();

    @BeforeAll
    static void start() {
        ES.start();
        es = ElasticsearchClient.of(
                b -> b.host("http://" + ES.getHttpHostAddress()).jsonMapper(new Jackson3JsonpMapper()));
    }

    @AfterAll
    static void stop() throws IOException {
        es.close();
        ES.stop();
    }

    IndexBootstrap bootstrap(IndexLayout layout) {
        return new IndexBootstrap(new ListingIndices(es), layout, CLOCK);
    }

    @Test
    @Order(1)
    void emptyCluster_planReportsEverythingMissing_andChangesNothing() {
        var report = bootstrap(layout).reconcile(Mode.PLAN);
        assertThat(report.hasDrift()).isTrue();
        assertThat(report.findings())
                .containsExactly(
                        new Finding.SynonymsMissing(
                                "listings-synonyms-en",
                                layout.synonyms(SearchLanguage.EN).size()),
                        new Finding.SynonymsMissing(
                                "listings-synonyms-fr",
                                layout.synonyms(SearchLanguage.FR).size()),
                        new Finding.IndexMissing("listings_en"),
                        new Finding.IndexMissing("listings_fr"));
        assertThat(new ListingIndices(es).aliased(SearchLanguage.EN)).isEmpty();
    }

    @Test
    @Order(2)
    void apply_createsSynonymSetsThenVersionedIndicesBehindTheAliases() {
        var report = bootstrap(layout).reconcile(Mode.APPLY);
        var en = "listings_en_v" + layout.schema() + "_20260930180000";
        var fr = "listings_fr_v" + layout.schema() + "_20260930180000";
        assertThat(report.findings())
                .containsExactly(
                        new Finding.SynonymsCreated(
                                "listings-synonyms-en",
                                layout.synonyms(SearchLanguage.EN).size()),
                        new Finding.SynonymsCreated(
                                "listings-synonyms-fr",
                                layout.synonyms(SearchLanguage.FR).size()),
                        new Finding.IndexCreated("listings_en", en),
                        new Finding.IndexCreated("listings_fr", fr));
        var indices = new ListingIndices(es);
        assertThat(indices.current(SearchLanguage.EN)).contains(en);
        assertThat(indices.current(SearchLanguage.FR)).contains(fr);
        assertThat(indices.all(SearchLanguage.FR)).containsExactly(fr);
        assertThat(indices.meta(en))
                .contains(layout.definition(SearchLanguage.EN).meta());
    }

    @Test
    @Order(3)
    void secondRun_isInSync() {
        var report = bootstrap(layout).reconcile(Mode.VERIFY);
        assertThat(report.hasDrift()).isFalse();
        assertThat(report.findings()).allMatch(f -> f instanceof Finding.InSync);
    }

    @Test
    @Order(4)
    void frenchAnalyzers_elisionFoldingStemming_englishPossessives() throws IOException {
        assertThat(tokens("listings_fr", "nl_text", "L'Épicerie du marché")).containsExactly("epic", "march");
        assertThat(tokens("listings_fr", "nl_prefix", "L'Épi")).containsExactly("epi");
        assertThat(tokens("listings_en", "nl_text", "Ravi's Mechanics")).containsExactly("ravi", "mechan");
        assertThat(tokens("listings_en", "nl_prefix", "Crème brûlée")).containsExactly("creme", "brulee");
    }

    @Test
    @Order(5)
    void synonyms_crossLanguage_inBothIndices() throws IOException {
        index("listings_en", "d1", "Country sourdough");
        index("listings_fr", "d2", "Pain au levain de campagne");
        index("listings_fr", "d3", "Mécanicien mobile");
        assertThat(search("listings_en", "pain au levain")).containsExactly("d1");
        assertThat(search("listings_fr", "sourdough")).containsExactly("d2");
        assertThat(search("listings_fr", "mobile mechanic")).containsExactly("d3");
        assertThat(search("listings_fr", "mecanicien")).containsExactly("d3");
    }

    @Test
    @Order(6)
    void aChangedSynonymFile_isLiveAtOnce_withoutAReindex() throws IOException {
        index("listings_en", "d4", "Wool beanie");
        assertThat(search("listings_en", "tuque")).isEmpty();
        var dir = copyOfLayout("changed-synonyms");
        Files.writeString(
                dir.resolve("synonyms-en.txt"), "\ntuque, toque, beanie\n", java.nio.file.StandardOpenOption.APPEND);
        var changedLayout = IndexLayout.fromDirectory(dir);

        assertThat(bootstrap(changedLayout).reconcile(Mode.PLAN).findings())
                .contains(new Finding.SynonymsChanged("listings-synonyms-en", 1, 0));
        var report = bootstrap(changedLayout).reconcile(Mode.APPLY);
        assertThat(report.findings()).contains(new Finding.SynonymsUpdated("listings-synonyms-en", 1, 0));
        assertThat(report.findings()).contains(new Finding.InSync("listings_en"));
        assertThat(search("listings_en", "tuque")).containsExactly("d4");
        // back to the packaged file for the next steps
        bootstrap(layout).reconcile(Mode.APPLY);
        assertThat(search("listings_en", "tuque")).isEmpty();
    }

    @Test
    @Order(7)
    void aNewField_isAddedInPlace() throws IOException {
        var dir = copyOfLayout("new-field");
        var listings = Files.readString(dir.resolve("listings.json"));
        Files.writeString(
                dir.resolve("listings.json"),
                listings.replace(
                        "\"sales30d\": { \"type\": \"integer\" },",
                        "\"sales30d\": { \"type\": \"integer\" },\n"
                                + "      \"repeatRate\": { \"type\": \"float\" },"));
        var withField = IndexLayout.fromDirectory(dir);

        assertThat(bootstrap(withField).reconcile(Mode.PLAN).findings())
                .contains(new Finding.MappingsChanged(
                        "listings_en",
                        new ListingIndices(es).current(SearchLanguage.EN).orElseThrow()));
        var applied = bootstrap(withField).reconcile(Mode.APPLY);
        assertThat(applied.findings())
                .filteredOn(f -> f instanceof Finding.MappingsUpdated)
                .hasSize(2);
        assertThat(bootstrap(withField).reconcile(Mode.VERIFY).hasDrift()).isFalse();
        // the packaged layout now lags behind the index: its mappings hash differs, and Elasticsearch never removes a
        // field, so going "back" is an additive no-op that just records the older hash
        assertThat(bootstrap(layout).reconcile(Mode.APPLY).findings())
                .filteredOn(f -> f instanceof Finding.MappingsUpdated)
                .hasSize(2);
    }

    @Test
    @Order(8)
    void changedAnalysisOrIncompatibleMappings_needAReindex_andNothingIsTouched() throws IOException {
        var dir = copyOfLayout("changed-analysis");
        Files.writeString(
                dir.resolve("analysis-en.json"),
                Files.readString(dir.resolve("analysis-en.json"))
                        .replace("\"language\": \"english\" }", "\"language\": \"light_english\" }"));
        var report = bootstrap(IndexLayout.fromDirectory(dir)).reconcile(Mode.APPLY);
        assertThat(report.findings())
                .filteredOn(f -> f instanceof Finding.ReindexRequired)
                .singleElement()
                .satisfies(
                        f -> assertThat(((Finding.ReindexRequired) f).reason()).contains("analyzers"));
        assertThat(report.hasDrift()).isTrue();

        var incompatible = copyOfLayout("incompatible");
        Files.writeString(
                incompatible.resolve("listings.json"),
                Files.readString(incompatible.resolve("listings.json"))
                        .replace(
                                "\"priceCents\": { \"type\": \"long\" }", "\"priceCents\": { \"type\": \"keyword\" }"));
        var refused = bootstrap(IndexLayout.fromDirectory(incompatible)).reconcile(Mode.APPLY);
        assertThat(refused.findings())
                .filteredOn(f -> f instanceof Finding.ReindexRequired)
                .hasSize(2)
                .allSatisfy(
                        f -> assertThat(((Finding.ReindexRequired) f).reason()).contains("incompatibly"));
        assertThat(bootstrap(layout).reconcile(Mode.VERIFY).hasDrift()).isFalse();
    }

    private Path copyOfLayout(String name) throws IOException {
        var dir = Files.createDirectories(changed.resolve(name));
        for (var file : List.of(
                "listings.json", "analysis-en.json", "analysis-fr.json", "synonyms-en.txt", "synonyms-fr.txt")) {
            try (var in = getClass().getClassLoader().getResourceAsStream("search/" + file)) {
                Files.write(dir.resolve(file), in.readAllBytes());
            }
        }
        return dir;
    }

    private static List<String> tokens(String index, String analyzer, String text) throws IOException {
        return es.indices().analyze(a -> a.index(index).analyzer(analyzer).text(text)).tokens().stream()
                .map(AnalyzeToken::token)
                .toList();
    }

    private static void index(String alias, String id, String name) throws IOException {
        var doc = "{\"id\":\"%s\",\"kind\":\"product\",\"market\":\"AB\",\"name\":\"%s\"}".formatted(id, name);
        es.index(i -> i.index(alias)
                .id(id)
                .withJson(new StringReader(doc))
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
    }

    private static List<String> search(String alias, String text) throws IOException {
        return es
                .search(
                        s -> s.index(alias)
                                .query(q -> q.match(m -> m.field("name").query(text))),
                        Void.class)
                .hits()
                .hits()
                .stream()
                .map(h -> h.id())
                .toList();
    }
}
