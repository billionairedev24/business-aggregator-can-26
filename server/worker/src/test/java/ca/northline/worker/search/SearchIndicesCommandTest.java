package ca.northline.worker.search;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.worker.support.WorkerContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
}
