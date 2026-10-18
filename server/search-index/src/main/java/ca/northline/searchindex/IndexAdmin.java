package ca.northline.searchindex;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a full reindex, a rollback and their tests need from a search engine (S-71, S-115), whichever serves the read
 * model: {@link ElasticsearchIndexAdmin} (aliases over versioned indices) or {@link MeilisearchIndexAdmin} (the live
 * index swaps its contents with a versioned one). Readers and writers only ever address {@link SearchLanguage#alias}.
 */
public interface IndexAdmin {

    /** The indices behind the live name of a language now (one, unless someone changed things by hand). */
    List<String> live(SearchLanguage language);

    /** Every versioned index of the language ({@code listings_<lang>_v<schema>_<stamp>}). */
    List<String> all(SearchLanguage language);

    /**
     * Creates the next versioned index of the language for a reindex to load, named after {@code at} (one second later
     * per name already taken); returns its name. It doesn't serve searches yet.
     */
    String createForLoading(SearchLanguage language, Instant at);

    /** Makes a loaded index ready to serve: the layout's serving settings back, everything written searchable. */
    void readyToServe(String index);

    /**
     * Makes {@code to} serve its languages, all of them in one atomic request; returns, per language, the indices that
     * now hold what was served before (to delete, or to keep for a rollback).
     */
    Map<SearchLanguage, List<String>> swap(Map<SearchLanguage, String> to);

    /**
     * The index a rollback of the language swaps back in, and since when the newer documents were written (what the
     * old index missed).
     *
     * @throws IllegalStateException when there is nothing to go back to
     */
    Rollback rollback(SearchLanguage language);

    /** Deletes a versioned index (never the live name). */
    void delete(String index);

    /** How many documents an index holds (after {@link #readyToServe}). */
    long count(String index);

    /** @param since when the documents served now started to be written (a rollback re-reads changes since then) */
    record Rollback(String to, Instant since) {}
}
