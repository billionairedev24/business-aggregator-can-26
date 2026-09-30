package ca.northline.worker.topics;

import ca.northline.worker.topics.TopicCatalogue.TopicSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;

/**
 * Makes a Kafka cluster match the topic catalogue through the admin API only (MSK SASL/SCRAM, Google Managed Kafka
 * SASL/PLAIN, Event Hubs' Kafka endpoint, local Kafka): no Strimzi, no broker access.
 *
 * <ul>
 *   <li>{@link Mode#PLAN} and {@link Mode#VERIFY} change nothing and report what {@code apply} would do.
 *   <li>{@link Mode#APPLY} creates missing topics with the catalogue's partitions and configs and sets drifted configs
 *       (retention, cleanup policy, min ISR) back to the catalogue's values.
 * </ul>
 *
 * Never deletes a topic, never changes a partition count (lowering is impossible; raising remaps keys and breaks
 * per-aggregate ordering), never changes the replication factor: those are reported as drift for a human. Topics that
 * exist but aren't in the catalogue are reported as unmanaged and left alone.
 */
@Slf4j
@RequiredArgsConstructor
public final class TopicProvisioner {

    private static final long TIMEOUT_SECONDS = 60;

    private final Admin admin;

    /** Replication factor for new topics; empty = the broker's {@code default.replication.factor}. */
    private final Optional<Short> replicationFactor;

    public enum Mode {
        PLAN,
        VERIFY,
        APPLY
    }

    /** One line of the report. {@link #drift()} = the cluster differs from the catalogue after this run. */
    public sealed interface Finding {
        String topic();

        default boolean drift() {
            return false;
        }

        record InSync(String topic) implements Finding {}

        record Created(String topic, int partitions) implements Finding {}

        record Missing(String topic, int partitions) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record ConfigCorrected(String topic, String key, String was, String now) implements Finding {}

        record ConfigDrift(String topic, String key, String actual, String expected) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        record PartitionDrift(String topic, int actual, int expected) implements Finding {
            @Override
            public boolean drift() {
                return true;
            }
        }

        /** The service doesn't answer DescribeConfigs for this topic (e.g. Event Hubs with a Send/Listen key). */
        record ConfigsUnreadable(String topic, String reason) implements Finding {}

        record Unmanaged(String topic) implements Finding {}
    }

    /** What a run found and did. */
    public record Report(Mode mode, List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }

        public boolean hasDrift() {
            return findings.stream().anyMatch(Finding::drift);
        }

        public <T extends Finding> List<T> all(Class<T> type) {
            return findings.stream().filter(type::isInstance).map(type::cast).toList();
        }

        public String summary() {
            var counts = findings.stream()
                    .collect(Collectors.groupingBy(f -> f.getClass().getSimpleName(), Collectors.counting()));
            return mode.name().toLowerCase(Locale.ROOT) + ": " + counts;
        }
    }

    public Report reconcile(List<TopicSpec> desired, Mode mode) {
        var existing = listTopics();
        var findings = new ArrayList<Finding>();
        var present = desired.stream().filter(s -> existing.contains(s.name())).toList();
        var missing = desired.stream().filter(s -> !existing.contains(s.name())).toList();

        if (mode == Mode.APPLY) {
            findings.addAll(create(missing));
        } else {
            missing.forEach(s -> findings.add(new Finding.Missing(s.name(), s.partitions())));
        }

        var descriptions = describe(present.stream().map(TopicSpec::name).toList());
        var configs = configs(present);
        var corrections = new ArrayList<Finding.ConfigCorrected>();
        for (var spec : present) {
            var before = findings.size();
            var partitions = Objects.requireNonNull(descriptions.get(spec.name()))
                    .partitions()
                    .size();
            if (partitions != spec.partitions()) {
                findings.add(new Finding.PartitionDrift(spec.name(), partitions, spec.partitions()));
            }
            switch (configs.getOrDefault(spec.name(), new Configs.Unreadable("not described"))) {
                case Configs.Unreadable(var reason) -> findings.add(new Finding.ConfigsUnreadable(spec.name(), reason));
                case Configs.Read(var actual) ->
                    spec.configs().forEach((key, expected) -> {
                        var value = Optional.ofNullable(actual.get(key)).orElse("");
                        if (!value.equals(expected)) {
                            if (mode == Mode.APPLY) {
                                corrections.add(new Finding.ConfigCorrected(spec.name(), key, value, expected));
                            } else {
                                findings.add(new Finding.ConfigDrift(spec.name(), key, value, expected));
                            }
                        }
                    });
            }
            if (findings.size() == before
                    && corrections.stream().noneMatch(c -> c.topic().equals(spec.name()))) {
                findings.add(new Finding.InSync(spec.name()));
            }
        }
        if (!corrections.isEmpty()) {
            alter(corrections);
            findings.addAll(corrections);
        }

        var managed = desired.stream().map(TopicSpec::name).collect(Collectors.toSet());
        existing.stream()
                .filter(name -> !managed.contains(name))
                .sorted()
                .forEach(name -> findings.add(new Finding.Unmanaged(name)));
        return new Report(mode, findings);
    }

    private sealed interface Configs {
        record Read(Map<String, String> values) implements Configs {}

        record Unreadable(String reason) implements Configs {}
    }

    private Set<String> listTopics() {
        return await(
                admin.listTopics(new ListTopicsOptions().listInternal(false)).names());
    }

    private Map<String, TopicDescription> describe(Collection<String> names) {
        return names.isEmpty() ? Map.of() : await(admin.describeTopics(names).allTopicNames());
    }

    private Map<String, Configs> configs(List<TopicSpec> specs) {
        var result = new HashMap<String, Configs>();
        var resources = specs.stream()
                .map(s -> new ConfigResource(ConfigResource.Type.TOPIC, s.name()))
                .toList();
        if (resources.isEmpty()) {
            return result;
        }
        admin.describeConfigs(resources).values().forEach((resource, future) -> {
            try {
                Config config = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                var values = new HashMap<String, String>();
                config.entries().stream().filter(e -> e.value() != null).forEach(e -> values.put(e.name(), e.value()));
                result.put(resource.name(), new Configs.Read(values));
            } catch (ExecutionException e) {
                result.put(resource.name(), new Configs.Unreadable(String.valueOf(e.getCause())));
            } catch (TimeoutException e) {
                result.put(resource.name(), new Configs.Unreadable("timed out"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        return result;
    }

    private List<Finding> create(List<TopicSpec> missing) {
        if (missing.isEmpty()) {
            return List.of();
        }
        var topics = missing.stream()
                .map(s -> new NewTopic(s.name(), Optional.of(s.partitions()), replicationFactor).configs(s.configs()))
                .toList();
        var results = admin.createTopics(topics).values();
        var findings = new ArrayList<Finding>();
        var failures = new ArrayList<String>();
        for (var spec : missing) {
            try {
                Objects.requireNonNull(results.get(spec.name())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                findings.add(new Finding.Created(spec.name(), spec.partitions()));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TopicExistsException) {
                    // Created concurrently (another Job, Terraform); the next run compares its settings.
                    findings.add(new Finding.InSync(spec.name()));
                } else {
                    failures.add(spec.name() + ": " + e.getCause());
                }
            } catch (TimeoutException e) {
                failures.add(spec.name() + ": timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Could not create topics: " + String.join("; ", failures));
        }
        return findings;
    }

    private void alter(List<Finding.ConfigCorrected> corrections) {
        var ops = corrections.stream()
                .collect(Collectors.groupingBy(
                        c -> new ConfigResource(ConfigResource.Type.TOPIC, c.topic()),
                        Collectors.mapping(
                                c -> new AlterConfigOp(new ConfigEntry(c.key(), c.now()), AlterConfigOp.OpType.SET),
                                Collectors.<AlterConfigOp>toList())));
        await(admin.incrementalAlterConfigs(Map.copyOf(ops)).all());
    }

    private static <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka admin call failed: " + e.getMessage(), e);
        }
    }
}
