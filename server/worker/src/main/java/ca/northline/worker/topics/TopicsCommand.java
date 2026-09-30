package ca.northline.worker.topics;

import ca.northline.worker.topics.TopicProvisioner.Finding;
import ca.northline.worker.topics.TopicProvisioner.Mode;
import ca.northline.worker.topics.TopicProvisioner.Report;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Provisions the Kafka topics of {@code deploy/kafka/topics.yaml} in any environment (S-25), without starting the
 * worker's consumers: a Helm pre-install/pre-upgrade hook Job, or by hand.
 *
 * <pre>
 * ./gradlew :worker:kafkaTopics --args='plan'     # what apply would do (exit 0)
 * ./gradlew :worker:kafkaTopics --args='apply'    # create missing topics, correct config drift; never deletes
 * ./gradlew :worker:kafkaTopics --args='verify'   # change nothing; exit 3 when anything is missing or drifted
 * java -cp @/app/jib-classpath-file ca.northline.worker.topics.TopicsCommand apply     # in the worker image
 * </pre>
 *
 * Connection settings are the worker's own ({@code spring.kafka.*} ← {@code KAFKA_BOOTSTRAP},
 * {@code KAFKA_SECURITY_PROTOCOL}, {@code KAFKA_SASL_MECHANISM}, {@code KAFKA_SASL_JAAS_CONFIG}); topic settings are
 * {@code northline.topics.*} ({@code KAFKA_REPLICATION_FACTOR}, {@code KAFKA_MIN_INSYNC_REPLICAS},
 * {@code KAFKA_TOPICS_CATALOGUE}). Exit codes: 0 done, 3 drift left ({@code verify}), 1 error.
 */
@Slf4j
public final class TopicsCommand {

    public static final int DRIFT = 3;

    private TopicsCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (RuntimeException e) {
            log.error("Kafka topics: {}", e.getMessage(), e);
            status = 1;
        }
        System.exit(status);
    }

    /** Runs {@code plan}, {@code verify} or {@code apply}; returns the exit status. */
    public static int run(String... args) {
        var mode = mode(args);
        try (var context = new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args)) {
            var properties = context.getBean(TopicsProperties.class);
            var catalogue = catalogue(properties);
            try (var admin = Admin.create(context.getBean(KafkaAdmin.class).getConfigurationProperties())) {
                var report = new TopicProvisioner(admin, properties.replication())
                        .reconcile(catalogue.desired(properties.extraConfigs()), mode);
                log(report);
                return mode == Mode.VERIFY && report.hasDrift() ? DRIFT : 0;
            }
        }
    }

    private static Mode mode(String... args) {
        var words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        var word = words.isEmpty() ? "plan" : words.getFirst();
        return switch (word) {
            case "plan", "verify", "apply" -> Mode.valueOf(word.toUpperCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("Unknown command " + word + ": plan | verify | apply");
        };
    }

    private static TopicCatalogue catalogue(TopicsProperties properties) {
        return properties
                .catalogueFile()
                .map(file -> {
                    try {
                        return TopicCatalogue.load(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read the topic catalogue " + file, e);
                    }
                })
                .orElseGet(TopicCatalogue::fromClasspath);
    }

    private static void log(Report report) {
        for (var finding : report.findings()) {
            switch (finding) {
                case Finding.InSync _ -> log.debug("ok        {}", finding.topic());
                case Finding.Created(var topic, var partitions) ->
                    log.info("CREATED   {} ({} partitions)", topic, partitions);
                case Finding.Missing(var topic, var partitions) ->
                    log.warn("MISSING   {} ({} partitions)", topic, partitions);
                case Finding.ConfigCorrected(var topic, var key, var was, var now) ->
                    log.info("CORRECTED {} {}: {} -> {}", topic, key, was.isEmpty() ? "(unset)" : was, now);
                case Finding.ConfigDrift(var topic, var key, var actual, var expected) ->
                    log.warn(
                            "DRIFT     {} {}: {}, catalogue {}",
                            topic,
                            key,
                            actual.isEmpty() ? "(unset)" : actual,
                            expected);
                case Finding.PartitionDrift(var topic, var actual, var expected) ->
                    log.warn(
                            "DRIFT     {} partitions: {}, catalogue {} (never changed automatically — runbook"
                                    + " infrastructure.md § 5.3)",
                            topic,
                            actual,
                            expected);
                case Finding.ConfigsUnreadable(var topic, var reason) ->
                    log.info("UNREAD    {} configs not readable here: {}", topic, reason);
                case Finding.Unmanaged(var topic) -> log.info("UNMANAGED {} (not in the catalogue; left as is)", topic);
            }
        }
        log.info("Kafka topics {}", report.summary());
    }

    /** Kafka auto-configuration and the topic settings only: no listeners, database or Elasticsearch. */
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @EnableConfigurationProperties(TopicsProperties.class)
    static class CommandContext {}
}
