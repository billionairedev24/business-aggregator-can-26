package ca.northline.searchindex;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IndexAdmin} on Elasticsearch: the aliases {@code listings_<lang>} over versioned indices. A reindex loads its
 * index with refresh and replicas off ({@link #LOADING}), then swaps the alias; the previous index stays behind the
 * alias until the swap and is what {@link #swap} returns.
 */
public final class ElasticsearchIndexAdmin implements IndexAdmin {

    public static final Map<String, String> LOADING = Map.of(
            "index.refresh_interval", "-1", "index.auto_expand_replicas", "false", "index.number_of_replicas", "0");
    static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(2);

    private final ListingIndices indices;
    private final IndexLayout layout;

    public ElasticsearchIndexAdmin(ListingIndices indices, IndexLayout layout) {
        this.indices = indices;
        this.layout = layout;
    }

    @Override
    public List<String> live(SearchLanguage language) {
        return indices.aliased(language);
    }

    @Override
    public List<String> all(SearchLanguage language) {
        return indices.all(language);
    }

    @Override
    public String createForLoading(SearchLanguage language, Instant at) {
        var taken = new ArrayList<>(indices.all(language));
        taken.addAll(indices.aliased(language));
        var bump = at;
        var name = layout.newIndexName(language, bump);
        while (taken.contains(name)) {
            bump = bump.plusSeconds(1);
            name = layout.newIndexName(language, bump);
        }
        indices.create(name, layout.definition(language), LOADING, false);
        return name;
    }

    @Override
    public void readyToServe(String index) {
        var language = language(index);
        var settings = layout.definition(language).settings();
        indices.putSettings(
                index,
                Map.of(
                        "index.refresh_interval",
                                settings.path("refresh_interval").asString("1s"),
                        "index.auto_expand_replicas",
                                settings.path("auto_expand_replicas").asString("0-1")));
        indices.refreshAndWait(index, HEALTH_TIMEOUT);
    }

    @Override
    public Map<SearchLanguage, List<String>> swap(Map<SearchLanguage, String> to) {
        var previous = new EnumMap<SearchLanguage, List<String>>(SearchLanguage.class);
        to.keySet().forEach(language -> previous.put(language, indices.aliased(language)));
        indices.swap(to);
        return Map.copyOf(previous);
    }

    /** The newest index older than the one the alias serves; since = when the served one was created. */
    @Override
    public Rollback rollback(SearchLanguage language) {
        var current = indices.current(language)
                .orElseThrow(() -> new IllegalStateException(
                        "The alias " + language.alias() + " doesn't point at exactly one index; fix it by hand"));
        var previous = indices.all(language).stream()
                .filter(name -> IndexLayout.createdAt(name).isBefore(IndexLayout.createdAt(current)))
                .max(Comparator.comparing(IndexLayout::createdAt))
                .orElseThrow(() -> new IllegalStateException(noOlder(language)));
        return new Rollback(previous, IndexLayout.createdAt(current));
    }

    @Override
    public void delete(String index) {
        indices.delete(index);
    }

    @Override
    public long count(String index) {
        return indices.count(index);
    }

    static SearchLanguage language(String index) {
        for (var language : SearchLanguage.values()) {
            if (IndexLayout.isIndexOf(language, index)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Not a listings index: " + index);
    }

    static String noOlder(SearchLanguage language) {
        return "No older " + language.alias()
                + " index to go back to: the reindex didn't keep it (run it with --keep-old next time)"
                + " — run a full reindex instead";
    }
}
