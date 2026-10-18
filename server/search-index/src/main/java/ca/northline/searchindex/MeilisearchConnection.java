package ca.northline.searchindex;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the Meilisearch of the {@code meilisearch} search provider is ({@code northline.search.meilisearch.*} in the
 * api and the worker; docs/runbooks/search.md § Meilisearch). TLS is the URL's: {@code https://…} for a hosted
 * instance (the JVM's trust store checks the certificate).
 *
 * @param url {@code MEILISEARCH_URL}
 * @param apiKey {@code MEILISEARCH_API_KEY}, sent as {@code Authorization: Bearer}; blank = none (a local instance
 *     without a master key)
 * @param timeout connect and read timeout of one HTTP request
 * @param taskTimeout how long a writer waits for Meilisearch to finish its tasks (writes are asynchronous there)
 */
public record MeilisearchConnection(
        @DefaultValue("http://localhost:7700") URI url,
        @DefaultValue("") String apiKey,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("60s") Duration taskTimeout) {

    public static MeilisearchConnection of(String url, String apiKey) {
        return new MeilisearchConnection(URI.create(url), apiKey, Duration.ofSeconds(10), Duration.ofSeconds(60));
    }
}
