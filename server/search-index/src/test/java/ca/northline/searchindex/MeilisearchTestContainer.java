package ca.northline.searchindex;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** One Meilisearch (the compose image, a master key) per test JVM, for the tests of the {@code meilisearch} provider. */
final class MeilisearchTestContainer {

    static final String IMAGE = "getmeili/meilisearch:v1.28.0";
    static final String MASTER_KEY = "test-master-key-obviously-fake-000";

    @SuppressWarnings("resource") // stopped by Testcontainers' reaper with the JVM
    private static final GenericContainer<?> MEILI = new GenericContainer<>(IMAGE)
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MASTER_KEY)
            .withEnv("MEILI_NO_ANALYTICS", "true")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private MeilisearchTestContainer() {}

    static synchronized String url() {
        if (!MEILI.isRunning()) {
            MEILI.start();
        }
        return "http://" + MEILI.getHost() + ":" + MEILI.getMappedPort(7700);
    }

    static MeilisearchClient client() {
        return MeilisearchClient.create(MeilisearchConnection.of(url(), MASTER_KEY));
    }

    /** Deletes every index (each test class starts from an empty instance). */
    static void clear(MeilisearchClient meili) {
        meili.await(meili.indexUids().stream().map(meili::deleteIndex).toList());
    }
}
