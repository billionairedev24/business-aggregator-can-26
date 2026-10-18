package ca.northline.searchindex;

import ca.northline.searchindex.IndexBootstrap.Finding;
import ca.northline.searchindex.IndexBootstrap.Mode;
import ca.northline.searchindex.IndexBootstrap.Report;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The search-indices deploy step on Meilisearch (provider {@code meilisearch}): makes {@code listings_en} and
 * {@code listings_fr} match the {@link MeilisearchLayout}, with the same modes and report as {@link IndexBootstrap}.
 *
 * <p>Meilisearch has no aliases and no separate synonym sets: the live index <em>is</em> {@code listings_<lang>}
 * (a reindex swaps its contents with a new index, {@link MeilisearchIndexAdmin}) and the synonyms are one of its
 * settings. So per language: the index missing → created with every setting; present → its synonyms
 * ({@code SynonymsChanged/Updated}, subject {@code listings-synonyms-<lang>} as on Elasticsearch) and its other
 * settings ({@code MappingsChanged/Updated}) are compared with the layout and, under {@link Mode#APPLY}, changed in
 * place. Meilisearch re-indexes by itself after a settings change, so nothing here ever requires a reindex; only a
 * change to the fields the writer derives does (a {@code schema} bump, the reindex Job). It never deletes an index.
 */
public final class MeilisearchBootstrap {

    /** Settings compared as sets: Meilisearch returns them sorted, their order means nothing. */
    private static final Set<String> UNORDERED = Set.of("filterableAttributes", "sortableAttributes", "stopWords");

    private final MeilisearchClient meili;
    private final MeilisearchLayout layout;

    public MeilisearchBootstrap(MeilisearchClient meili, MeilisearchLayout layout) {
        this.meili = meili;
        this.layout = layout;
    }

    public Report reconcile(Mode mode) {
        var synonyms = new ArrayList<Finding>();
        var indices = new ArrayList<Finding>();
        for (var language : SearchLanguage.values()) {
            var alias = language.alias();
            var set = language.synonymsSet();
            var desired = layout.settings(language);
            var desiredSynonyms = desired.remove("synonyms");
            var rules = desiredSynonyms == null ? 0 : desiredSynonyms.size();
            if (meili.index(alias).isEmpty()) {
                if (mode != Mode.APPLY) {
                    synonyms.add(new Finding.SynonymsMissing(set, rules));
                    indices.add(new Finding.IndexMissing(alias));
                    continue;
                }
                var all = layout.settings(language);
                meili.await(meili.createIndex(alias, layout.primaryKey()), meili.updateSettings(alias, all));
                synonyms.add(new Finding.SynonymsCreated(set, rules));
                indices.add(new Finding.IndexCreated(alias, alias));
                continue;
            }
            var actual = meili.settings(alias);
            synonyms.add(synonyms(alias, set, desiredSynonyms, actual.path("synonyms"), mode));
            indices.add(settings(alias, desired, actual, mode));
        }
        var findings = new ArrayList<Finding>(synonyms);
        findings.addAll(indices);
        return new Report(layout.schema(), List.copyOf(findings));
    }

    private Finding synonyms(String alias, String set, JsonNode desired, JsonNode actual, Mode mode) {
        var want = entries(desired);
        var have = entries(actual);
        var added = want.stream().filter(e -> !have.contains(e)).count();
        var removed = have.stream().filter(e -> !want.contains(e)).count();
        if (added == 0 && removed == 0) {
            return new Finding.InSync(set);
        }
        if (mode != Mode.APPLY) {
            return new Finding.SynonymsChanged(set, (int) added, (int) removed);
        }
        meili.await(meili.updateSettings(
                alias, JsonNodeFactory.instance.objectNode().set("synonyms", desired)));
        return new Finding.SynonymsUpdated(set, (int) added, (int) removed);
    }

    private Finding settings(String alias, ObjectNode desired, JsonNode actual, Mode mode) {
        var changed = JsonNodeFactory.instance.objectNode();
        desired.properties().forEach(e -> {
            if (!covers(e.getKey(), e.getValue(), actual.path(e.getKey()))) {
                changed.set(e.getKey(), e.getValue());
            }
        });
        if (changed.isEmpty()) {
            return new Finding.InSync(alias);
        }
        if (mode != Mode.APPLY) {
            return new Finding.MappingsChanged(alias, alias + " (" + String.join(", ", changed.propertyNames()) + ")");
        }
        meili.await(meili.updateSettings(alias, changed));
        return new Finding.MappingsUpdated(alias, alias + " (" + String.join(", ", changed.propertyNames()) + ")");
    }

    /** True when {@code actual} has everything {@code desired} sets (Meilisearch adds defaults for the rest). */
    static boolean covers(String key, JsonNode desired, JsonNode actual) {
        if (desired.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            return desired.properties().stream()
                    .allMatch(e -> covers(e.getKey(), e.getValue(), actual.path(e.getKey())));
        }
        if (desired.isArray() && UNORDERED.contains(key)) {
            return actual.isArray() && sorted(desired).equals(sorted(actual));
        }
        if (desired.isNumber() && actual.isNumber()) {
            return desired.asDouble() == actual.asDouble();
        }
        return desired.equals(actual);
    }

    /** Sorted, in Unicode NFC: Meilisearch may hand back {@code à} decomposed (NFD). */
    private static List<String> sorted(JsonNode array) {
        return array.valueStream().map(n -> nfc(n.asString())).sorted().toList();
    }

    private static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /** {@code term → synonym} pairs of a synonym map. */
    private static Set<String> entries(JsonNode synonyms) {
        var out = new TreeSet<String>();
        if (synonyms.isObject()) {
            synonyms.properties()
                    .forEach(e -> e.getValue().forEach(v -> out.add(nfc(e.getKey()) + " → " + nfc(v.asString()))));
        }
        return out;
    }
}
