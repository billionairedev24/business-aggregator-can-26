package ca.northline.searchindex;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * Makes an Elasticsearch cluster match the {@link IndexLayout} — the search-indices deploy step (S-42), run before
 * every release like the Kafka topics Job:
 *
 * <ol>
 *   <li>synonym sets first (the analyzers reference them): created when missing, replaced when the file changed —
 *       Elasticsearch reloads the search analyzers, no reindex;
 *   <li>per language, the alias {@code listings_<lang>}: missing → a new versioned index is created behind it; present
 *       → its index's {@code _meta} is compared with the layout. Same hashes = in sync. New mappings only → added in
 *       place ({@code PUT _mapping}; Elasticsearch refuses anything that isn't additive, which then counts as
 *       "reindex required"). Different analysis or schema → reindex required (S-71): reported, never done here.
 * </ol>
 *
 * {@link Mode#PLAN} and {@link Mode#VERIFY} change nothing; {@link Mode#APPLY} does everything but a reindex. It never
 * deletes an index or a synonym set.
 */
@RequiredArgsConstructor
public final class IndexBootstrap {

    private final ListingIndices indices;
    private final IndexLayout layout;
    private final Clock clock;

    public enum Mode {
        PLAN,
        VERIFY,
        APPLY
    }

    /** What was found (and, under {@link Mode#APPLY}, done) for one synonym set or alias. */
    public sealed interface Finding {
        String subject();

        /** Something {@code apply} would change, or can't: drift left behind. */
        default boolean drift() {
            return false;
        }

        record InSync(String subject) implements Finding {}

        record SynonymsMissing(String subject, int rules) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record SynonymsCreated(String subject, int rules) implements Finding {}

        record SynonymsChanged(String subject, int added, int removed) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record SynonymsUpdated(String subject, int added, int removed) implements Finding {}

        record IndexMissing(String subject) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record IndexCreated(String subject, String index) implements Finding {}

        record MappingsChanged(String subject, String index) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record MappingsUpdated(String subject, String index) implements Finding {}

        /** Only a new index built from Postgres can bring the alias in line (S-71, docs/runbooks/search.md). */
        record ReindexRequired(String subject, String index, String reason) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }
    }

    public record Report(int schema, List<Finding> findings) {
        public boolean hasDrift() {
            return findings.stream().anyMatch(Finding::drift);
        }

        public String summary() {
            return findings.stream()
                    .collect(Collectors.groupingBy(f -> f.getClass().getSimpleName(), Collectors.counting()))
                    .entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ", "layout schema v" + schema + ": ", ""));
        }
    }

    public Report reconcile(Mode mode) {
        var findings = new ArrayList<Finding>();
        for (var language : SearchLanguage.values()) {
            findings.add(synonyms(language, mode));
        }
        for (var language : SearchLanguage.values()) {
            findings.add(index(language, mode));
        }
        return new Report(layout.schema(), List.copyOf(findings));
    }

    private Finding synonyms(SearchLanguage language, Mode mode) {
        var set = language.synonymsSet();
        var desired = layout.synonyms(language);
        var actual = indices.synonyms(set);
        if (actual.isEmpty()) {
            if (mode != Mode.APPLY) {
                return new Finding.SynonymsMissing(set, desired.size());
            }
            indices.putSynonyms(set, desired);
            return new Finding.SynonymsCreated(set, desired.size());
        }
        var added = desired.stream().filter(r -> !actual.get().contains(r)).count();
        var removed = actual.get().stream().filter(r -> !desired.contains(r)).count();
        if (added == 0 && removed == 0) {
            return new Finding.InSync(set);
        }
        if (mode != Mode.APPLY) {
            return new Finding.SynonymsChanged(set, (int) added, (int) removed);
        }
        indices.putSynonyms(set, desired);
        return new Finding.SynonymsUpdated(set, (int) added, (int) removed);
    }

    private Finding index(SearchLanguage language, Mode mode) {
        var alias = language.alias();
        var definition = layout.definition(language);
        var aliased = indices.aliased(language);
        if (aliased.isEmpty()) {
            if (mode != Mode.APPLY) {
                return new Finding.IndexMissing(alias);
            }
            var index = layout.newIndexName(language, clock.instant());
            indices.create(index, definition, Map.of(), true);
            return new Finding.IndexCreated(alias, index);
        }
        if (aliased.size() > 1) {
            return new Finding.ReindexRequired(
                    alias, String.join(",", aliased), "the alias points at " + aliased.size() + " indices");
        }
        var index = aliased.getFirst();
        var meta = indices.meta(index);
        if (meta.isEmpty()) {
            return new Finding.ReindexRequired(
                    alias, index, "the index has no _meta.northline (not made from the layout)");
        }
        var live = meta.get();
        if (live.schema() != definition.schema()) {
            return new Finding.ReindexRequired(
                    alias, index, "schema v" + live.schema() + " live, v" + definition.schema() + " in the layout");
        }
        if (!live.analysisHash().equals(definition.analysisHash())) {
            return new Finding.ReindexRequired(alias, index, "settings or analyzers changed");
        }
        if (live.mappingsHash().equals(definition.mappingsHash())) {
            return new Finding.InSync(alias);
        }
        if (mode != Mode.APPLY) {
            return new Finding.MappingsChanged(alias, index);
        }
        try {
            indices.putMappings(index, definition);
            return new Finding.MappingsUpdated(alias, index);
        } catch (ElasticsearchException e) {
            if (e.status() != 400) {
                throw e;
            }
            return new Finding.ReindexRequired(alias, index, "mappings changed incompatibly: " + e.getMessage());
        }
    }
}
