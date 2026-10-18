package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The packaged Meilisearch layout (deploy/search/meilisearch.json + stop words + the shared synonym files). */
class MeilisearchLayoutTest {

    final MeilisearchLayout layout = MeilisearchLayout.fromClasspath();

    @Test
    void bothLanguages_sameSettings_theirOwnLocaleStopWordsAndSynonyms() {
        var en = layout.settings(SearchLanguage.EN);
        var fr = layout.settings(SearchLanguage.FR);
        assertThat(en.path("searchableAttributes").valueStream().map(n -> n.asString()))
                .containsExactly("name", "merchantName", "categoryNames", "keywords", "description");
        assertThat(en.path("filterableAttributes")).isEqualTo(fr.path("filterableAttributes"));
        assertThat(en.path("rankingRules").get(0).asString()).isEqualTo("sort");
        assertThat(en.path("pagination").path("maxTotalHits").asInt()).isEqualTo(10_000);
        assertThat(en.path("localizedAttributes").get(0).path("locales").get(0).asString())
                .isEqualTo("eng");
        assertThat(fr.path("localizedAttributes").get(0).path("locales").get(0).asString())
                .isEqualTo("fra");
        assertThat(en.path("stopWords").valueStream().map(n -> n.asString()))
                .contains("the", "with")
                .doesNotContain("le", "and"); // "and" is a word of "fries and gravy"
        assertThat(fr.path("stopWords").valueStream().map(n -> n.asString())).contains("le", "du", "l", "qu");
        assertThat(en.path("synonyms").path("sourdough").valueStream().map(n -> n.asString()))
                .containsExactly("levain", "pain au levain");
        assertThat(fr.path("synonyms").has("pain au levain")).isTrue();
        assertThat(layout.schema()).isPositive();
        assertThat(layout.primaryKey()).isEqualTo("id");
    }

    @Test
    void solrRules_becomeMeilisearchSynonymMaps() {
        var map = MeilisearchLayout.synonyms(List.of("tire, tyre, pneu", "tire, wheel", "ac => air conditioning"));
        assertThat(map.path("tire").valueStream().map(n -> n.asString()))
                .containsExactly("pneu", "tyre", "wheel"); // in two rules: both
        assertThat(map.path("tyre").valueStream().map(n -> n.asString())).containsExactly("pneu", "tire");
        assertThat(map.path("ac").valueStream().map(n -> n.asString())).containsExactly("air conditioning");
        assertThat(map.has("air conditioning")).isFalse(); // one way
    }

    @Test
    void wordsOfMultiWordSynonyms_areNotStopWords() {
        assertThat(MeilisearchLayout.phraseWords(List.of("sourdough, pain au levain", "winter tires, pneus d'hiver")))
                .containsExactlyInAnyOrder("pain", "au", "levain", "winter", "tires", "pneus", "d", "hiver");
        var fr = layout.settings(SearchLanguage.FR)
                .path("stopWords")
                .valueStream()
                .map(n -> n.asString())
                .toList();
        assertThat(fr).doesNotContain("au", "d").contains("le", "les");
    }

    @Test
    void wordFiles_skipCommentsBlanksAndDuplicates() {
        assertThat(MeilisearchLayout.words("# c\nThe\n\n the\nand\n")).containsExactly("the", "and");
    }
}
