package ca.northline.worker.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.worker.topics.TopicProvisioner.Finding;
import ca.northline.worker.topics.TopicProvisioner.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The provisioner and scripts/topics.sh against a real Kafka 4 (the compose image): create, idempotence, drift
 * reports and corrections, never deleting — one broker, the steps in order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TopicProvisionerTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static Admin admin;

    final TopicCatalogue catalogue = TopicCatalogue.fromClasspath();
    final List<TopicCatalogue.TopicSpec> desired = catalogue.desired();

    @BeforeAll
    static void start() {
        KAFKA.start();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    @AfterAll
    static void stop() {
        admin.close();
        KAFKA.stop();
    }

    TopicProvisioner provisioner() {
        return new TopicProvisioner(admin, Optional.of((short) 1));
    }

    @Test
    @Order(1)
    void scriptsTopicsShCreatesTheCatalogueInsideTheKafkaImage() throws Exception {
        // As the compose one-shot does: the script + catalogue in the apache/kafka image (busybox awk), its CLI.
        KAFKA.copyFileToContainer(Transferable.of(read("scripts/topics.sh"), 0755), "/tmp/topics.sh");
        KAFKA.copyFileToContainer(Transferable.of(read("deploy/kafka/topics.yaml")), "/tmp/topics.yaml");
        var env = "KAFKA_TOPICS_CATALOGUE=/tmp/topics.yaml KAFKA_TOPICS_CMD=/opt/kafka/bin/kafka-topics.sh"
                + " KAFKA_TOPICS_BOOTSTRAP=localhost:9093";
        // Pre-existing topic with the wrong retention: reported, not touched by the script.
        admin.createTopics(
                        List.of(new NewTopic("identity.user", 6, (short) 1).configs(Map.of("retention.ms", "1000000"))))
                .all()
                .get();

        var first = KAFKA.execInContainer("sh", "-c", env + " sh /tmp/topics.sh");
        assertThat(first.getExitCode())
                .as(first.getStdout() + first.getStderr())
                .isZero();
        assertThat(first.getStdout())
                .contains("CREATE payments.payout (partitions 6, retention.ms 604800000)")
                .contains("CREATE payments.payout.dlq (partitions 1, retention.ms 2592000000)")
                .contains("CREATE food.menu.search-indexer.retry-2 (partitions 6, retention.ms 86400000)")
                .contains("DRIFT  identity.user retention.ms: 1000000, catalogue 604800000")
                .contains(desired.size() + " topics in the catalogue: " + (desired.size() - 1)
                        + " created, 0 unchanged, 1 with drift");

        var strict = KAFKA.execInContainer("sh", "-c", env + " KAFKA_TOPICS_STRICT=1 sh /tmp/topics.sh");
        assertThat(strict.getExitCode()).isEqualTo(3);
        assertThat(strict.getStdout()).contains("0 created, " + (desired.size() - 1) + " unchanged, 1 with drift");

        // The Java side reads the script's topics as the catalogue's, except the drift it was told about.
        var report = provisioner().reconcile(desired, Mode.VERIFY);
        assertThat(report.all(Finding.ConfigDrift.class))
                .containsExactly(new Finding.ConfigDrift("identity.user", "retention.ms", "1000000", "604800000"));
        assertThat(report.all(Finding.InSync.class)).hasSize(desired.size() - 1);
        assertThat(report.all(Finding.Missing.class)).isEmpty();
    }

    @Test
    @Order(2)
    void applyCorrectsConfigDriftCreatesWhatIsMissingAndIsIdempotent() throws Exception {
        admin.deleteTopics(List.of("payments.refund.dlq")).all().get();
        awaitGone("payments.refund.dlq");

        var plan = provisioner().reconcile(desired, Mode.PLAN);
        assertThat(plan.all(Finding.Missing.class)).containsExactly(new Finding.Missing("payments.refund.dlq", 1));
        assertThat(topics()).doesNotContain("payments.refund.dlq"); // plan changes nothing

        var apply = provisioner().reconcile(desired, Mode.APPLY);
        assertThat(apply.all(Finding.Created.class)).containsExactly(new Finding.Created("payments.refund.dlq", 1));
        assertThat(apply.all(Finding.ConfigCorrected.class))
                .containsExactly(new Finding.ConfigCorrected("identity.user", "retention.ms", "1000000", "604800000"));
        assertThat(apply.hasDrift()).isFalse();
        // Config changes reach the broker's metadata asynchronously (KRaft): read until they show.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(retention("identity.user")).isEqualTo("604800000");
            assertThat(retention("payments.refund.dlq")).isEqualTo("2592000000");
        });

        var again = await().atMost(Duration.ofSeconds(10))
                .until(() -> provisioner().reconcile(desired, Mode.PLAN), r -> !r.hasDrift());
        assertThat(again.all(Finding.Missing.class)).isEmpty();
        again = provisioner().reconcile(desired, Mode.APPLY);
        assertThat(again.all(Finding.InSync.class)).hasSize(desired.size());
        assertThat(again.hasDrift()).isFalse();
    }

    @Test
    @Order(3)
    void partitionDriftAndUnmanagedTopicsAreReportedNeverChanged() throws Exception {
        admin.createPartitions(Map.of("booking.quote", NewPartitions.increaseTo(8)))
                .all()
                .get();
        admin.createTopics(List.of(new NewTopic("legacy.topic", 1, (short) 1)))
                .all()
                .get();
        admin.incrementalAlterConfigs(Map.of(
                        new ConfigResource(ConfigResource.Type.TOPIC, "trust.review.dlq"),
                        List.of(new AlterConfigOp(
                                new ConfigEntry("cleanup.policy", "compact"), AlterConfigOp.OpType.SET))))
                .all()
                .get();

        var verify = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> provisioner().reconcile(desired, Mode.VERIFY),
                        r -> r.all(Finding.ConfigDrift.class).size() == 1
                                && r.all(Finding.PartitionDrift.class).size() == 1);
        assertThat(verify.hasDrift()).isTrue();
        assertThat(verify.all(Finding.PartitionDrift.class))
                .containsExactly(new Finding.PartitionDrift("booking.quote", 8, 6));
        assertThat(verify.all(Finding.ConfigDrift.class))
                .containsExactly(new Finding.ConfigDrift("trust.review.dlq", "cleanup.policy", "compact", "delete"));
        assertThat(verify.all(Finding.Unmanaged.class)).containsExactly(new Finding.Unmanaged("legacy.topic"));

        var apply = provisioner().reconcile(desired, Mode.APPLY);
        assertThat(apply.all(Finding.PartitionDrift.class)).hasSize(1); // still there: a human decides
        assertThat(apply.all(Finding.ConfigCorrected.class)).hasSize(1);
        assertThat(topics()).contains("legacy.topic"); // never deleted
        assertThat(admin.describeTopics(List.of("booking.quote"))
                        .allTopicNames()
                        .get()
                        .get("booking.quote")
                        .partitions())
                .hasSize(8);
    }

    @Test
    @Order(4)
    void commandUsesTheWorkersKafkaSettingsAndReportsDriftAsExitCode() {
        var bootstrap = "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers();
        // booking.quote still has 8 partitions: verify fails, apply succeeds (drift reported), plan never fails.
        assertThat(TopicsCommand.run("verify", bootstrap)).isEqualTo(TopicsCommand.DRIFT);
        assertThat(TopicsCommand.run("plan", bootstrap)).isZero();
        assertThat(TopicsCommand.run("apply", bootstrap, "--northline.topics.min-insync-replicas=1"))
                .isZero();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(config("payments.payout", "min.insync.replicas"))
                        .isEqualTo("1"));
    }

    private Set<String> topics() throws Exception {
        return admin.listTopics().names().get();
    }

    private String retention(String topic) throws Exception {
        return config(topic, "retention.ms");
    }

    private String config(String topic, String key) {
        try {
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            return admin.describeConfigs(List.of(resource))
                    .all()
                    .get()
                    .get(resource)
                    .get(key)
                    .value();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitGone(String topic) throws Exception {
        for (var i = 0; i < 50 && topics().contains(topic); i++) {
            Thread.sleep(100);
        }
    }

    private static String read(String path) throws Exception {
        var repo = java.nio.file.Path.of(System.getProperty("northline.repo", "../.."));
        return java.nio.file.Files.readString(repo.resolve(path), StandardCharsets.UTF_8);
    }
}
