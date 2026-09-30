package ca.northline.searchindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The packaged layout (deploy/search): both languages, the naming, the hashes and the synonym files. */
class IndexLayoutTest {

    final IndexLayout layout = IndexLayout.fromClasspath();
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void bothLanguagesShareTheFields_eachWithItsOwnAnalysis() {
        var en = layout.definition(SearchLanguage.EN);
        var fr = layout.definition(SearchLanguage.FR);
        assertThat(en.mappings()).isEqualTo(fr.mappings());
        assertThat(en.mappingsHash()).isEqualTo(fr.mappingsHash());
        assertThat(en.analysisHash()).isNotEqualTo(fr.analysisHash());
        assertThat(en.settings().at("/analysis/filter/nl_synonyms/synonyms_set").asString())
                .isEqualTo("listings-synonyms-en");
        assertThat(fr.settings().at("/analysis/filter/nl_synonyms/synonyms_set").asString())
                .isEqualTo("listings-synonyms-fr");
        assertThat(fr.settings().at("/analysis/filter/nl_elision/type").asString())
                .isEqualTo("elision");
        assertThat(fr.settings().at("/analysis/analyzer/nl_text/filter").toString())
                .contains("asciifolding");
        assertThat(en.settings().has("$comment")).isFalse();
        assertThat(en.settings().at("/analysis").has("$comment")).isFalse();
    }

    @Test
    void theMappingsHaveEveryFieldTheStoryNames() {
        var properties = layout.definition(SearchLanguage.EN).mappings().get("properties");
        assertThat(properties.propertyNames())
                .contains(
                        "kind",
                        "categoryPath",
                        "priceCents",
                        "rating",
                        "trustTier",
                        "openHours",
                        "inStock",
                        "deliveryCutoffMinute",
                        "merchantStatus",
                        "vetting",
                        "location",
                        "serviceRadiusKm",
                        "suggest");
        assertThat(properties.at("/location/type").asString()).isEqualTo("geo_point");
        assertThat(properties.at("/suggest/type").asString()).isEqualTo("completion");
        assertThat(properties.at("/name/fields/prefix/type").asString()).isEqualTo("search_as_you_type");
        assertThat(layout.definition(SearchLanguage.EN)
                        .mappings()
                        .get("dynamic")
                        .asString())
                .isEqualTo("strict");
    }

    @Test
    void indicesAreVersionedAndServedThroughTheLanguageAlias() {
        var name = layout.newIndexName(SearchLanguage.FR, Instant.parse("2026-09-30T18:04:05Z"));
        assertThat(name).isEqualTo("listings_fr_v" + layout.schema() + "_20260930180405");
        assertThat(SearchLanguage.FR.alias()).isEqualTo("listings_fr");
        assertThat(IndexLayout.isIndexOf(SearchLanguage.FR, name)).isTrue();
        assertThat(IndexLayout.isIndexOf(SearchLanguage.EN, name)).isFalse();
        assertThat(IndexLayout.isIndexOf(SearchLanguage.FR, "listings_fr")).isFalse();
    }

    @Test
    void theCreateBodyCarriesMetaAliasAndOverrides() {
        var definition = layout.definition(SearchLanguage.EN);
        var body = json.readTree(definition.createBody(Map.of("refresh_interval", "-1"), true));
        assertThat(body.at("/settings/refresh_interval").asString()).isEqualTo("-1");
        assertThat(body.at("/mappings/_meta/northline/mappingsHash").asString()).isEqualTo(definition.mappingsHash());
        assertThat(body.at("/mappings/_meta/northline/schema").asInt()).isEqualTo(layout.schema());
        assertThat(body.at("/aliases/listings_en/is_write_index").asBoolean()).isTrue();
        var withoutAlias = json.readTree(definition.createBody(Map.of(), false));
        assertThat(withoutAlias.has("aliases")).isFalse();
        assertThat(withoutAlias.at("/settings/refresh_interval").asString()).isEqualTo("1s");
    }

    @Test
    void hashesIgnoreKeyOrder() {
        assertThat(IndexLayout.hash(json.readTree("{\"a\":1,\"b\":{\"c\":[1,{\"e\":2,\"d\":3}]}}")))
                .isEqualTo(IndexLayout.hash(json.readTree("{\"b\":{\"c\":[1,{\"d\":3,\"e\":2}]},\"a\":1}")));
        assertThat(IndexLayout.hash(json.readTree("{\"c\":[1,2]}")))
                .isNotEqualTo(IndexLayout.hash(json.readTree("{\"c\":[2,1]}")));
    }

    @Test
    void synonymFilesAreSolrRules_bilingualInBothLanguages() {
        assertThat(layout.synonyms(SearchLanguage.EN)).contains("sourdough, pain au levain, levain");
        assertThat(layout.synonyms(SearchLanguage.FR)).contains("pain au levain, levain, sourdough");
        assertThat(layout.synonyms(SearchLanguage.EN)).noneMatch(r -> r.startsWith("#") || r.isBlank());
        assertThat(IndexLayout.parseSynonyms("# comment\n\na, b\nc => d\n")).containsExactly("a, b", "c => d");
        assertThatThrownBy(() -> IndexLayout.parseSynonyms("a, b\nlonely\n")).hasMessageContaining("line 2");
    }

    @Test
    void languageFromTags() {
        assertThat(SearchLanguage.of("fr-CA")).isEqualTo(SearchLanguage.FR);
        assertThat(SearchLanguage.of("FR")).isEqualTo(SearchLanguage.FR);
        assertThat(SearchLanguage.of("en-CA")).isEqualTo(SearchLanguage.EN);
        assertThat(SearchLanguage.of(null)).isEqualTo(SearchLanguage.EN);
    }
}
