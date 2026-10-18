package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The reindex's index operations on Meilisearch, which has no aliases: a versioned index loaded beside the live one,
 * both languages swapped in one task (the versioned names then hold the previous documents), the rollback swapping
 * them back, and deletes that never touch the live names.
 */
class MeilisearchIndexAdminTest {

    static MeilisearchClient meili;
    static MeilisearchIndexAdmin admin;

    @BeforeAll
    static void start() {
        meili = MeilisearchTestContainer.client();
        admin = new MeilisearchIndexAdmin(meili, MeilisearchLayout.fromClasspath());
    }

    @Test
    void loadBeside_swap_rollBack_delete() {
        MeilisearchTestContainer.clear(meili);
        new MeilisearchBootstrap(meili, MeilisearchLayout.fromClasspath()).reconcile(IndexBootstrap.Mode.APPLY);
        put("listings_en", "OLD");
        put("listings_fr", "OLD");
        assertThat(admin.live(SearchLanguage.EN)).containsExactly("listings_en");

        var at = Instant.parse("2026-10-18T12:00:00Z");
        var en = admin.createForLoading(SearchLanguage.EN, at);
        var fr = admin.createForLoading(SearchLanguage.FR, at);
        assertThat(en).isEqualTo("listings_en_v1_20261018120000");
        assertThat(admin.createForLoading(SearchLanguage.EN, at)).isEqualTo("listings_en_v1_20261018120001");
        admin.delete("listings_en_v1_20261018120001");
        assertThat(meili.settings(en).path("rankingRules"))
                .isEqualTo(meili.settings("listings_en").path("rankingRules"));
        put(en, "NEW");
        put(fr, "NEW");
        admin.readyToServe(en);
        assertThat(admin.count(en)).isEqualTo(1);

        var retired = admin.swap(Map.of(SearchLanguage.EN, en, SearchLanguage.FR, fr));
        assertThat(retired).isEqualTo(Map.of(SearchLanguage.EN, List.of(en), SearchLanguage.FR, List.of(fr)));
        assertThat(meili.document("listings_en", "NEW")).isPresent();
        assertThat(meili.document("listings_fr", "NEW")).isPresent();
        assertThat(meili.document(en, "OLD")).isPresent(); // the previous documents, kept under the new name

        var rollback = admin.rollback(SearchLanguage.EN);
        assertThat(rollback).isEqualTo(new IndexAdmin.Rollback(en, at));
        admin.swap(Map.of(
                SearchLanguage.EN,
                en,
                SearchLanguage.FR,
                admin.rollback(SearchLanguage.FR).to()));
        assertThat(meili.document("listings_en", "OLD")).isPresent();
        assertThat(meili.document("listings_fr", "OLD")).isPresent();

        assertThatThrownBy(() -> admin.delete("listings_en")).isInstanceOf(IllegalArgumentException.class);
        admin.delete(en);
        admin.delete(fr);
        assertThat(admin.all(SearchLanguage.EN)).isEmpty();
        assertThatThrownBy(() -> admin.rollback(SearchLanguage.EN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("No older listings_en index to go back to");
    }

    private static void put(String index, String id) {
        var docs = JsonNodeFactory.instance.arrayNode();
        docs.addObject().put("id", id).put("name", id).put("market", "AB");
        meili.await(meili.addDocuments(index, docs));
    }
}
