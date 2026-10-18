package ca.northline.worker.support;

import ca.northline.worker.topics.TopicCatalogue;
import ca.northline.worker.topics.TopicProvisioner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.flywaydb.core.Flyway;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka 4 and one PostGIS 17 per test JVM. The database gets the repository's db/migrations (the tables the worker
 * reads are the api's); Kafka gets every topic of the catalogue plus the test consumers' ({@link #TEST_TOPIC}).
 */
public final class WorkerContainers {

    public static final String TEST_TOPIC = "testing.event";
    public static final String TEST_GROUP = "worker-test";
    public static final String BYSTANDER_GROUP = "worker-bystander";

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("northline")
            .withUsername("northline")
            .withPassword("northline");

    /** Elasticsearch 9 (the compose image, security off), started only by the tests that need it ({@link #elastic}). */
    public static final ElasticsearchContainer ELASTIC = newElastic();

    /** Meilisearch (the compose image, a master key) for the {@code meilisearch} provider's tests ({@link #meilisearch}). */
    public static final String MEILI_KEY = "test-master-key-obviously-fake-000";

    @SuppressWarnings("resource") // stopped by Testcontainers' reaper with the JVM
    public static final org.testcontainers.containers.GenericContainer<?> MEILI =
            new org.testcontainers.containers.GenericContainer<>(java.util.Objects.requireNonNullElse(
                            System.getenv("MEILISEARCH_TEST_IMAGE"), "getmeili/meilisearch:v1.28.0"))
                    .withExposedPorts(7700)
                    .withEnv("MEILI_MASTER_KEY", MEILI_KEY)
                    .withEnv("MEILI_NO_ANALYTICS", "true")
                    .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/health")
                            .forStatusCode(200))
                    .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private static boolean started;
    private static boolean indicesCreated;
    private static boolean meiliIndexes;

    private WorkerContainers() {}

    public static synchronized void start() {
        if (started) {
            return;
        }
        Stream.of(KAFKA, POSTGRES).parallel().forEach(c -> c.start());
        var repo = Path.of(System.getProperty("northline.repo", "../.."));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + repo.resolve("db/migrations"))
                .load()
                .migrate();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            new TopicProvisioner(admin, Optional.of((short) 1))
                    .reconcile(catalogue().desired(), TopicProvisioner.Mode.APPLY);
        }
        started = true;
    }

    /** A new Elasticsearch 9 container like the compose one: HTTP, security off, a small heap. */
    public static ElasticsearchContainer newElastic() {
        return new ElasticsearchContainer(DockerImageName.parse("elasticsearch:9.1.3")
                        .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withStartupTimeout(java.time.Duration.ofMinutes(4)); // slow under a full parallel build
    }

    /** Starts Elasticsearch once per test JVM; returns its {@code http://host:port}. */
    public static synchronized String elastic() {
        if (!ELASTIC.isRunning()) {
            ELASTIC.start();
        }
        return "http://" + ELASTIC.getHttpHostAddress();
    }

    /** {@link #elastic()} with the synonym sets and the listings indices created (the deploy step, once). */
    public static synchronized String elasticWithIndices() {
        var uri = elastic();
        if (!indicesCreated) {
            try (var es = co.elastic.clients.elasticsearch.ElasticsearchClient.of(
                    b -> b.host(uri).jsonMapper(new co.elastic.clients.json.jackson.Jackson3JsonpMapper()))) {
                new ca.northline.searchindex.IndexBootstrap(
                                new ca.northline.searchindex.ListingIndices(es),
                                ca.northline.searchindex.IndexLayout.fromClasspath(),
                                java.time.Clock.systemUTC())
                        .reconcile(ca.northline.searchindex.IndexBootstrap.Mode.APPLY);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            indicesCreated = true;
        }
        return uri;
    }

    /** Starts Meilisearch once per test JVM with the listings indexes created (the deploy step); returns its URL. */
    public static synchronized String meilisearch() {
        if (!MEILI.isRunning()) {
            MEILI.start();
        }
        var url = "http://" + MEILI.getHost() + ":" + MEILI.getMappedPort(7700);
        if (!meiliIndexes) {
            new ca.northline.searchindex.MeilisearchBootstrap(
                            meilisearchClient(), ca.northline.searchindex.MeilisearchLayout.fromClasspath())
                    .reconcile(ca.northline.searchindex.IndexBootstrap.Mode.APPLY);
            meiliIndexes = true;
        }
        return url;
    }

    public static ca.northline.searchindex.MeilisearchClient meilisearchClient() {
        if (!MEILI.isRunning()) {
            MEILI.start();
        }
        return ca.northline.searchindex.MeilisearchClient.create(ca.northline.searchindex.MeilisearchConnection.of(
                "http://" + MEILI.getHost() + ":" + MEILI.getMappedPort(7700), MEILI_KEY));
    }

    /** The real catalogue plus the test consumers' topic and groups. */
    public static TopicCatalogue catalogue() {
        var real = TopicCatalogue.fromClasspath();
        return new TopicCatalogue(
                real.defaults(),
                real.dlq(),
                real.retry(),
                Stream.concat(
                                real.topics().stream(),
                                Stream.of(new TopicCatalogue.Topic(TEST_TOPIC, "testing", 2, null, null)))
                        .toList(),
                Stream.concat(
                                real.consumers().stream(),
                                Stream.of(
                                        new TopicCatalogue.Consumer(TEST_GROUP, List.of(TEST_TOPIC), List.of(1, 2)),
                                        new TopicCatalogue.Consumer(BYSTANDER_GROUP, List.of(TEST_TOPIC), List.of(1))))
                        .toList());
    }
}
