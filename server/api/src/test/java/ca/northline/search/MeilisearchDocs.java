package ca.northline.search;

import ca.northline.searchindex.IndexBootstrap;
import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.MeilisearchBootstrap;
import ca.northline.searchindex.MeilisearchClient;
import ca.northline.searchindex.MeilisearchConnection;
import ca.northline.searchindex.MeilisearchDocuments;
import ca.northline.searchindex.MeilisearchLayout;
import ca.northline.searchindex.SearchLanguage;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Meilisearch for the search API tests of the {@code meilisearch} provider (the compose image, a master key), with the
 * listings indexes of deploy/search/meilisearch.json, and documents written exactly as the worker writes them
 * ({@link MeilisearchDocuments}).
 */
final class MeilisearchDocs {

    static final String MASTER_KEY = "test-master-key-obviously-fake-000";

    @SuppressWarnings("resource") // stopped by Testcontainers' reaper with the JVM
    static final GenericContainer<?> MEILI = new GenericContainer<>(java.util.Objects.requireNonNullElse(
                    System.getenv("MEILISEARCH_TEST_IMAGE"),
                    "getmeili/meilisearch:v1.28.0")) // another version: the env var
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MASTER_KEY)
            .withEnv("MEILI_NO_ANALYTICS", "true")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private static boolean ready;

    private MeilisearchDocs() {}

    static synchronized String start() {
        if (!ready) {
            MEILI.start();
            new MeilisearchBootstrap(client(), MeilisearchLayout.fromClasspath()).reconcile(IndexBootstrap.Mode.APPLY);
            ready = true;
        }
        return url();
    }

    static String url() {
        return "http://" + MEILI.getHost() + ":" + MEILI.getMappedPort(7700);
    }

    static MeilisearchClient client() {
        return MeilisearchClient.create(MeilisearchConnection.of(url(), MASTER_KEY));
    }

    /** Writes documents into both languages (French ones may differ) and waits until they are searchable. */
    static void index(List<ListingDocument> en, List<ListingDocument> fr) {
        var meili = client();
        var tasks = new java.util.ArrayList<Long>();
        if (!en.isEmpty()) {
            tasks.add(meili.addDocuments(SearchLanguage.EN.alias(), documents(en)));
        }
        if (!fr.isEmpty()) {
            tasks.add(meili.addDocuments(SearchLanguage.FR.alias(), documents(fr)));
        }
        meili.await(tasks);
    }

    private static tools.jackson.databind.node.ArrayNode documents(List<ListingDocument> docs) {
        var array = JsonNodeFactory.instance.arrayNode();
        docs.forEach(d -> array.add(MeilisearchDocuments.toMeilisearch(d)));
        return array;
    }
}
