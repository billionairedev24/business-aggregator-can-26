package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.searchindex.IndexBootstrap.Finding;
import ca.northline.searchindex.IndexBootstrap.Mode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The search-indices deploy step on a real Meilisearch (the compose image): create, idempotence, a synonym change and
 * a settings drift applied in place, and what the settings do to a search (stop words, elision, accents, synonyms) —
 * the steps in order, like {@link IndexBootstrapTest} on Elasticsearch.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeilisearchBootstrapTest {

    static MeilisearchClient meili;

    final MeilisearchLayout layout = MeilisearchLayout.fromClasspath();

    @BeforeAll
    static void start() {
        meili = MeilisearchTestContainer.client();
        MeilisearchTestContainer.clear(meili);
    }

    MeilisearchBootstrap bootstrap(MeilisearchLayout layout) {
        return new MeilisearchBootstrap(meili, layout);
    }

    int synonyms(SearchLanguage language) {
        return layout.settings(language).path("synonyms").size();
    }

    @Test
    @Order(1)
    void emptyInstance_planReportsEverythingMissing_andChangesNothing() {
        var report = bootstrap(layout).reconcile(Mode.PLAN);
        assertThat(report.hasDrift()).isTrue();
        assertThat(report.findings())
                .containsExactly(
                        new Finding.SynonymsMissing("listings-synonyms-en", synonyms(SearchLanguage.EN)),
                        new Finding.SynonymsMissing("listings-synonyms-fr", synonyms(SearchLanguage.FR)),
                        new Finding.IndexMissing("listings_en"),
                        new Finding.IndexMissing("listings_fr"));
        assertThat(meili.index("listings_en")).isEmpty();
    }

    @Test
    @Order(2)
    void apply_createsBothIndexesWithEverySetting() {
        var report = bootstrap(layout).reconcile(Mode.APPLY);
        assertThat(report.findings())
                .containsExactly(
                        new Finding.SynonymsCreated("listings-synonyms-en", synonyms(SearchLanguage.EN)),
                        new Finding.SynonymsCreated("listings-synonyms-fr", synonyms(SearchLanguage.FR)),
                        new Finding.IndexCreated("listings_en", "listings_en"),
                        new Finding.IndexCreated("listings_fr", "listings_fr"));
        var settings = meili.settings("listings_fr");
        assertThat(settings.path("rankingRules").get(0).asString()).isEqualTo("sort");
        assertThat(settings.path("filterableAttributes").valueStream().map(n -> n.asString()))
                .contains("openSlots", "categoryPath", "_geo");
        assertThat(meili.index("listings_fr").orElseThrow().path("primaryKey").asString())
                .isEqualTo("id");
    }

    @Test
    @Order(3)
    void secondRun_isInSync() {
        var report = bootstrap(layout).reconcile(Mode.VERIFY);
        assertThat(report.findings()).allMatch(f -> f instanceof Finding.InSync, "in sync: " + report.findings());
        assertThat(report.hasDrift()).isFalse();
    }

    @Test
    @Order(4)
    void theSettingsAtWork_stopWordsElisionAccentsSynonyms() {
        var en = JsonNodeFactory.instance.arrayNode();
        en.addObject().put("id", "A1").put("name", "Country sourdough").put("market", "AB");
        en.addObject().put("id", "A2").put("name", "Pain au levain de campagne").put("market", "AB");
        en.addObject().put("id", "A3").put("name", "Café of the corner").put("market", "AB");
        var fr = JsonNodeFactory.instance.arrayNode();
        fr.addObject().put("id", "F1").put("name", "L'Épicerie du marché").put("market", "AB");
        meili.await(meili.addDocuments("listings_en", en), meili.addDocuments("listings_fr", fr));

        assertThat(ids("listings_en", "sourdough")).containsExactlyInAnyOrder("A1", "A2"); // synonyms
        assertThat(ids("listings_en", "cafe")).containsExactly("A3"); // accents never decide a match
        assertThat(ids("listings_en", "the corner cafe")).containsExactly("A3"); // "the" is a stop word
        assertThat(ids("listings_fr", "epicerie marche")).containsExactly("F1"); // elision, folding
        assertThat(ids("listings_fr", "l'épicerie")).containsExactly("F1");
    }

    @Test
    @Order(5)
    void aChangedSynonymFile_isReported_thenReplacedInPlace() {
        var changed = MeilisearchLayout.load(name ->
                name.equals("synonyms-en.txt") ? classpath(name) + "\nsnowblower, snow thrower\n" : classpath(name));
        var plan = bootstrap(changed).reconcile(Mode.PLAN);
        assertThat(plan.findings()).contains(new Finding.SynonymsChanged("listings-synonyms-en", 2, 0));
        assertThat(plan.findings())
                .contains(new Finding.InSync("listings-synonyms-fr"), new Finding.InSync("listings_en"));

        var apply = bootstrap(changed).reconcile(Mode.APPLY);
        assertThat(apply.findings()).contains(new Finding.SynonymsUpdated("listings-synonyms-en", 2, 0));
        assertThat(meili.settings("listings_en")
                        .path("synonyms")
                        .path("snowblower")
                        .get(0)
                        .asString())
                .isEqualTo("snow thrower");
        assertThat(bootstrap(changed).reconcile(Mode.VERIFY).hasDrift()).isFalse();
        // and back to the packaged file (the next deploy of the old file does the same)
        assertThat(bootstrap(layout).reconcile(Mode.APPLY).findings())
                .contains(new Finding.SynonymsUpdated("listings-synonyms-en", 0, 2));
    }

    @Test
    @Order(6)
    void aSettingChangedByHand_isDrift_andApplyPutsItBack() {
        var hand = JsonNodeFactory.instance.objectNode();
        hand.putArray("sortableAttributes").add("id");
        meili.await(meili.updateSettings("listings_fr", hand));

        var verify = bootstrap(layout).reconcile(Mode.VERIFY);
        assertThat(verify.hasDrift()).isTrue();
        assertThat(verify.findings())
                .contains(new Finding.MappingsChanged("listings_fr", "listings_fr (sortableAttributes)"));
        var apply = bootstrap(layout).reconcile(Mode.APPLY);
        assertThat(apply.findings())
                .contains(new Finding.MappingsUpdated("listings_fr", "listings_fr (sortableAttributes)"));
        assertThat(bootstrap(layout).reconcile(Mode.VERIFY).findings()).contains(new Finding.InSync("listings_fr"));
    }

    private static List<String> ids(String index, String q) {
        var body = JsonNodeFactory.instance.objectNode().put("q", q).put("matchingStrategy", "all");
        return meili.search(index, body)
                .path("hits")
                .valueStream()
                .map(h -> h.path("id").asString())
                .toList();
    }

    private static String classpath(String name) {
        try (InputStream in = MeilisearchBootstrapTest.class.getClassLoader().getResourceAsStream("search/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
