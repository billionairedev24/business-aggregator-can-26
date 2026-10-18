package ca.northline.worker.search;

import java.util.List;

/**
 * Where {@link SearchProjection} writes (S-43): the engine behind {@code northline.search.provider} —
 * {@link ElasticsearchListingWriter} or {@link MeilisearchListingWriter}. Called under the merchant's advisory lock,
 * so the writes of one merchant arrive one refresh after the other.
 */
interface ListingWriter {

    /**
     * Indexes the built documents and deletes {@code deletes} in every language's target, in one go per engine;
     * returns what happened.
     *
     * @param version Postgres' {@code clock_timestamp()} in µs, taken under the merchant lock
     */
    SearchProjection.Outcome write(
            SearchProjection.Targets targets, DocumentBuilder.Built built, List<String> deletes, long version);

    /** Ids the index holds for a merchant (its own document included), including what was written a moment ago. */
    List<String> indexedIds(String index, String merchantId);
}
