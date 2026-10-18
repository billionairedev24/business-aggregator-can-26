package ca.northline.worker.search;

import ca.northline.searchindex.ElasticsearchIndexAdmin;
import ca.northline.searchindex.IndexAdmin;
import ca.northline.searchindex.IndexBootstrap;
import ca.northline.searchindex.IndexLayout;
import ca.northline.searchindex.ListingIndices;
import ca.northline.searchindex.MeilisearchBootstrap;
import ca.northline.searchindex.MeilisearchClient;
import ca.northline.searchindex.MeilisearchIndexAdmin;
import ca.northline.searchindex.MeilisearchLayout;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The search engine behind {@code northline.search.provider} ({@code SEARCH_PROVIDER}), for the worker's beans and its
 * one-off commands (bootstrap, reindex): the writer, the index administration of a reindex, and the deploy step.
 * Elasticsearch's client comes from Spring Boot ({@code spring.elasticsearch.*}) and is only asked for when it is the
 * engine; Meilisearch's is made here from {@code northline.search.meilisearch.*}.
 */
final class SearchEngine {

    private final SearchProperties properties;
    private final Supplier<ElasticsearchClient> elasticsearch;
    private @Nullable MeilisearchClient meilisearch;

    SearchEngine(SearchProperties properties, Supplier<ElasticsearchClient> elasticsearch) {
        this.properties = properties;
        this.elasticsearch = elasticsearch;
    }

    String provider() {
        return properties.provider();
    }

    boolean meilisearch() {
        return properties.provider().equals(SearchProperties.MEILISEARCH);
    }

    ListingWriter writer() {
        return meilisearch()
                ? new MeilisearchListingWriter(meili())
                : new ElasticsearchListingWriter(elasticsearch.get());
    }

    IndexAdmin admin() {
        return meilisearch()
                ? new MeilisearchIndexAdmin(meili(), MeilisearchLayout.fromClasspath())
                : new ElasticsearchIndexAdmin(new ListingIndices(elasticsearch.get()), IndexLayout.fromClasspath());
    }

    /** The search-indices deploy step (S-42) on the engine. */
    IndexBootstrap.Report bootstrap(IndexBootstrap.Mode mode) {
        return meilisearch()
                ? new MeilisearchBootstrap(meili(), MeilisearchLayout.fromClasspath()).reconcile(mode)
                : new IndexBootstrap(
                                new ListingIndices(elasticsearch.get()), IndexLayout.fromClasspath(), Clock.systemUTC())
                        .reconcile(mode);
    }

    private synchronized MeilisearchClient meili() {
        var client = meilisearch;
        if (client == null) {
            client = MeilisearchClient.create(properties.meilisearch());
            meilisearch = client;
        }
        return client;
    }
}
