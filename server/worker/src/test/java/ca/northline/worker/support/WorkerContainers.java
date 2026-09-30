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

    private static boolean started;

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
