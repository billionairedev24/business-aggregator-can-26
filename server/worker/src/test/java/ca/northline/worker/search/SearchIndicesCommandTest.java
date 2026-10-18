package ca.northline.worker.search;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.worker.support.WorkerContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * The deploy step as the Job runs it: the packaged layout against an empty Elasticsearch 9 of its own (the shared one
 * may already hold the indices other tests created), with the worker's settings.
 */
class SearchIndicesCommandTest {

    static final ElasticsearchContainer ES = WorkerContainers.newElastic();

    @BeforeAll
    static void start() {
        ES.start();
    }

    @AfterAll
    static void stop() {
        ES.stop();
    }

    @Test
    void verifyFailsOnAnEmptyCluster_applyBuildsIt_thenVerifyPasses() {
        var uris = "--spring.elasticsearch.uris=http://" + ES.getHttpHostAddress();
        assertThat(SearchIndicesCommand.run("plan", uris)).isZero();
        assertThat(SearchIndicesCommand.run("verify", uris)).isEqualTo(SearchIndicesCommand.DRIFT);
        assertThat(SearchIndicesCommand.run("apply", uris)).isZero();
        assertThat(SearchIndicesCommand.run("verify", uris)).isZero();
        assertThat(SearchIndicesCommand.run("apply", uris)).isZero();
    }

    /** The same Job with {@code SEARCH_PROVIDER=meilisearch}, against an empty Meilisearch of its own. */
    @Test
    void meilisearch_verifyFailsOnAnEmptyInstance_applyBuildsIt_thenVerifyPasses() {
        try (var meili = new GenericContainer<>(WorkerContainers.MEILI.getDockerImageName())
                .withExposedPorts(7700)
                .withEnv("MEILI_MASTER_KEY", WorkerContainers.MEILI_KEY)
                .withEnv("MEILI_NO_ANALYTICS", "true")
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))) {
            meili.start();
            var args = new String[] {
                "--northline.search.provider=meilisearch",
                "--northline.search.meilisearch.url=http://" + meili.getHost() + ":" + meili.getMappedPort(7700),
                "--northline.search.meilisearch.api-key=" + WorkerContainers.MEILI_KEY
            };
            assertThat(SearchIndicesCommand.run(with("plan", args))).isZero();
            assertThat(SearchIndicesCommand.run(with("verify", args))).isEqualTo(SearchIndicesCommand.DRIFT);
            assertThat(SearchIndicesCommand.run(with("apply", args))).isZero();
            assertThat(SearchIndicesCommand.run(with("verify", args))).isZero();
            assertThat(SearchIndicesCommand.run(with("apply", args))).isZero();
        }
    }

    private static String[] with(String command, String... args) {
        var all = new String[args.length + 1];
        all[0] = command;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }
}
