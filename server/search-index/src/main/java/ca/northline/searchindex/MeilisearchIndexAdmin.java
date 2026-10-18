package ca.northline.searchindex;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IndexAdmin} on Meilisearch, which has no aliases: the live index is {@code listings_<lang>} itself, and a
 * reindex loads a versioned index {@code listings_<lang>_v<schema>_<stamp>} beside it, then <b>swaps</b> the two
 * ({@code POST /swap-indexes}: documents and settings change places, every language in one atomic task). After the
 * swap the versioned name holds the <em>previous</em> documents — that is what {@link #swap} returns, what the reindex
 * deletes, and what {@code --keep-old} keeps. A rollback swaps the newest kept index back in.
 */
public final class MeilisearchIndexAdmin implements IndexAdmin {

    private final MeilisearchClient meili;
    private final MeilisearchLayout layout;

    public MeilisearchIndexAdmin(MeilisearchClient meili, MeilisearchLayout layout) {
        this.meili = meili;
        this.layout = layout;
    }

    @Override
    public List<String> live(SearchLanguage language) {
        return meili.index(language.alias()).isPresent() ? List.of(language.alias()) : List.of();
    }

    @Override
    public List<String> all(SearchLanguage language) {
        return meili.indexUids().stream()
                .filter(uid -> IndexLayout.isIndexOf(language, uid))
                .sorted()
                .toList();
    }

    @Override
    public String createForLoading(SearchLanguage language, Instant at) {
        var taken = all(language);
        var bump = at;
        var name = layout.newIndexName(language, bump);
        while (taken.contains(name)) {
            bump = bump.plusSeconds(1);
            name = layout.newIndexName(language, bump);
        }
        meili.await(
                meili.createIndex(name, layout.primaryKey()), meili.updateSettings(name, layout.settings(language)));
        return name;
    }

    /** Meilisearch serves what it has indexed: wait until the index has no task left. */
    @Override
    public void readyToServe(String index) {
        meili.awaitIdle(index);
    }

    @Override
    public Map<SearchLanguage, List<String>> swap(Map<SearchLanguage, String> to) {
        var pairs = new java.util.ArrayList<List<String>>();
        var previous = new EnumMap<SearchLanguage, List<String>>(SearchLanguage.class);
        to.forEach((language, index) -> {
            if (meili.index(language.alias()).isEmpty()) {
                throw new IllegalStateException(
                        language.alias() + " doesn't exist: run the search-indices Job (apply) before a reindex");
            }
            pairs.add(List.of(language.alias(), index));
            previous.put(language, List.of(index));
        });
        meili.await(meili.swap(pairs));
        return Map.copyOf(previous);
    }

    /**
     * The newest versioned index (a {@code --keep-old} run's previous documents); since = when it was created, i.e.
     * when the reindex that replaced its documents started.
     */
    @Override
    public Rollback rollback(SearchLanguage language) {
        if (meili.index(language.alias()).isEmpty()) {
            throw new IllegalStateException(language.alias() + " doesn't exist; nothing to roll back");
        }
        var kept = all(language).stream()
                .max(Comparator.comparing(IndexLayout::createdAt))
                .orElseThrow(() -> new IllegalStateException(ElasticsearchIndexAdmin.noOlder(language)));
        return new Rollback(kept, IndexLayout.createdAt(kept));
    }

    @Override
    public void delete(String index) {
        ElasticsearchIndexAdmin.language(index); // only versioned indices, never the live name
        meili.await(meili.deleteIndex(index));
    }

    @Override
    public long count(String index) {
        return meili.documentCount(index);
    }
}
