package ca.northline.worker.search;

import ca.northline.searchindex.MeilisearchConnection;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.search.*} (docs/runbooks/search.md).
 *
 * @param provider the engine the read model is written to: {@code elasticsearch} ({@code spring.elasticsearch.*}) or
 *     {@code meilisearch} ({@code meilisearch.*}) — the same as the api's {@code SEARCH_PROVIDER}
 * @param meilisearch where Meilisearch is ({@code MEILISEARCH_URL}, {@code MEILISEARCH_API_KEY}: a key that may write
 *     documents and manage the listings indexes)
 * @param reconcile the sweep of rows changed without an event
 */
@ConfigurationProperties("northline.search")
public record SearchProperties(
        @DefaultValue("elasticsearch") String provider,
        @DefaultValue MeilisearchConnection meilisearch,
        @DefaultValue Reconcile reconcile) {

    public static final String ELASTICSEARCH = "elasticsearch";
    public static final String MEILISEARCH = "meilisearch";

    public SearchProperties {
        if (!provider.equals(ELASTICSEARCH) && !provider.equals(MEILISEARCH)) {
            throw new IllegalStateException(
                    "northline.search.provider (SEARCH_PROVIDER) must be elasticsearch or meilisearch, not "
                            + provider);
        }
    }

    /**
     * @param enabled run the sweep (every replica tries; one holds the lease at a time)
     * @param every pause between two sweeps
     * @param overlap how far back each sweep looks before the last watermark (transactions still committing)
     * @param batch merchants refreshed per sweep at most (the rest wait for the next one)
     * @param lease how long a replica owns the sweep before another may take over
     */
    public record Reconcile(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1m") Duration every,
            @DefaultValue("2m") Duration overlap,
            @DefaultValue("500") int batch,
            @DefaultValue("5m") Duration lease) {}
}
