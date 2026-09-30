package ca.northline.worker.topics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Kafka topic catalogue {@code deploy/kafka/topics.yaml} (S-25): the topics the api publishes to, and the consumer
 * groups of the worker with their retry policy. {@link #desired()} derives every topic that must exist — each topic,
 * its {@code .dlq}, and {@code <topic>.<group>.retry-<n>} per consumer and retry delay — exactly as
 * {@code scripts/topics.sh} does (TopicCatalogueTest compares the two).
 */
public record TopicCatalogue(Defaults defaults, Dlq dlq, Retry retry, List<Topic> topics, List<Consumer> consumers) {

    /** Classpath location inside the worker (copied from {@code deploy/kafka/topics.yaml} by processResources). */
    public static final String CLASSPATH_LOCATION = "kafka/topics.yaml";

    public static final String DLQ_SUFFIX = ".dlq";

    private static final Pattern TOPIC_NAME = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
    private static final Pattern GROUP_NAME = Pattern.compile("[a-z][a-z0-9-]*");

    public TopicCatalogue {
        topics = List.copyOf(topics);
        consumers = List.copyOf(consumers);
        var names = new HashSet<String>();
        for (var t : topics) {
            if (!names.add(t.name())) {
                throw new IllegalArgumentException("Topic listed twice: " + t.name());
            }
        }
        var groups = new HashSet<String>();
        for (var c : consumers) {
            if (!groups.add(c.group())) {
                throw new IllegalArgumentException("Consumer group listed twice: " + c.group());
            }
            c.topics().stream().filter(t -> !names.contains(t)).findFirst().ifPresent(t -> {
                throw new IllegalArgumentException("Consumer " + c.group() + " reads unknown topic " + t);
            });
        }
    }

    /** Partitions / retention / cleanup policy of a topic that doesn't set its own. */
    public record Defaults(int partitions, int retentionHours, String cleanupPolicy) {}

    /** Every topic's dead-letter topic {@code <topic>.dlq}. */
    public record Dlq(int partitions, int retentionHours) {}

    /** Every consumer's retry topics {@code <topic>.<group>.retry-<n>} (the source topic's partition count). */
    public record Retry(int retentionHours) {}

    /** A {@code <module>.<aggregate>} topic; {@code owner} is the publishing module. */
    public record Topic(
            String name,
            String owner,
            @Nullable Integer partitions,
            @Nullable Integer retentionHours,
            @Nullable String cleanupPolicy) {
        public Topic {
            if (!TOPIC_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Topic name must be <module>.<aggregate>: " + name);
            }
        }
    }

    /** A consumer group of server/worker, the topics it reads and its non-blocking retry delays. */
    public record Consumer(String group, List<String> topics, List<Integer> retryDelaysSeconds) {
        public Consumer {
            if (!GROUP_NAME.matcher(group).matches()) {
                throw new IllegalArgumentException("Consumer group must be lower-case words with dashes: " + group);
            }
            topics = List.copyOf(topics);
            retryDelaysSeconds = List.copyOf(retryDelaysSeconds);
            if (retryDelaysSeconds.stream().anyMatch(d -> d <= 0)) {
                throw new IllegalArgumentException("Retry delays of " + group + " must be positive");
            }
        }

        /** Delivery attempts including the first one: {@code @RetryableTopic(attempts)}. */
        public int attempts() {
            return retryDelaysSeconds.size() + 1;
        }

        /** Retry topic suffix for {@code @RetryableTopic(retryTopicSuffix)}; Spring appends {@code -<index>}. */
        public String retryTopicSuffix() {
            return "." + group + ".retry";
        }
    }

    /** A topic that must exist, with the configuration the catalogue fixes. */
    public record TopicSpec(String name, int partitions, Map<String, String> configs) {
        public TopicSpec {
            configs = Map.copyOf(configs);
        }
    }

    public static TopicCatalogue load(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static TopicCatalogue fromClasspath() {
        try (var in = TopicCatalogue.class.getClassLoader().getResourceAsStream(CLASSPATH_LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("classpath:" + CLASSPATH_LOCATION + " is missing");
            }
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read classpath:" + CLASSPATH_LOCATION, e);
        }
    }

    public static TopicCatalogue load(InputStream in) {
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        var d = map(root, "defaults");
        var q = map(root, "dlq");
        var r = map(root, "retry");
        var topics = list(root, "topics").stream()
                .map(t -> new Topic(
                        text(t, "name"),
                        text(t, "owner"),
                        optionalNumber(t, "partitions"),
                        optionalNumber(t, "retentionHours"),
                        optionalText(t, "cleanupPolicy")))
                .toList();
        var consumers = list(root, "consumers").stream()
                .map(c -> new Consumer(
                        text(c, "group"),
                        values(c, "topics").stream().map(String::valueOf).toList(),
                        values(c, "retryDelaysSeconds").stream()
                                .map(v -> ((Number) v).intValue())
                                .toList()))
                .toList();
        return new TopicCatalogue(
                new Defaults(number(d, "partitions"), number(d, "retentionHours"), text(d, "cleanupPolicy")),
                new Dlq(number(q, "partitions"), number(q, "retentionHours")),
                new Retry(number(r, "retentionHours")),
                topics,
                consumers);
    }

    public Optional<Consumer> consumer(String group) {
        return consumers.stream().filter(c -> c.group().equals(group)).findFirst();
    }

    public Optional<Topic> topic(String name) {
        return topics.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public int partitionsOf(Topic topic) {
        return topic.partitions() != null ? topic.partitions() : defaults.partitions();
    }

    /** Every topic that must exist, in the order scripts/topics.sh prints them, with {@code extraConfigs} added. */
    public List<TopicSpec> desired(Map<String, String> extraConfigs) {
        var specs = new ArrayList<TopicSpec>();
        for (var t : topics) {
            var retention = t.retentionHours() != null ? t.retentionHours() : defaults.retentionHours();
            var cleanup = t.cleanupPolicy() != null ? t.cleanupPolicy() : defaults.cleanupPolicy();
            specs.add(spec(t.name(), partitionsOf(t), retention, cleanup, extraConfigs));
            specs.add(spec(
                    t.name() + DLQ_SUFFIX,
                    dlq.partitions(),
                    dlq.retentionHours(),
                    defaults.cleanupPolicy(),
                    extraConfigs));
        }
        for (var c : consumers) {
            for (var name : c.topics()) {
                var source = topic(name).orElseThrow();
                for (var i = 0; i < c.retryDelaysSeconds().size(); i++) {
                    specs.add(spec(
                            name + c.retryTopicSuffix() + "-" + i,
                            partitionsOf(source),
                            retry.retentionHours(),
                            defaults.cleanupPolicy(),
                            extraConfigs));
                }
            }
        }
        return List.copyOf(specs);
    }

    public List<TopicSpec> desired() {
        return desired(Map.of());
    }

    private static TopicSpec spec(
            String name, int partitions, int retentionHours, String cleanup, Map<String, String> extraConfigs) {
        var configs = new LinkedHashMap<String, String>(extraConfigs);
        configs.put("retention.ms", Long.toString(retentionHours * 3_600_000L));
        configs.put("cleanup.policy", cleanup);
        return new TopicSpec(name, partitions, configs);
    }

    // ---- YAML helpers (the catalogue is small and flat; SnakeYAML's safe constructor gives maps and lists) ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof Map<?, ?> v)) {
            throw new IllegalArgumentException("Catalogue needs a `" + key + "` section");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return m.get(key) instanceof List<?> v ? (List<Map<String, Object>>) v : List.of();
    }

    private static List<?> values(Map<String, Object> m, String key) {
        return m.get(key) instanceof List<?> v ? v : List.of();
    }

    private static String text(Map<String, Object> m, String key) {
        return Optional.ofNullable(optionalText(m, key))
                .orElseThrow(() -> new IllegalArgumentException("Missing `" + key + "` in " + m));
    }

    private static @Nullable String optionalText(Map<String, Object> m, String key) {
        var v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int number(Map<String, Object> m, String key) {
        return Optional.ofNullable(optionalNumber(m, key))
                .orElseThrow(() -> new IllegalArgumentException("Missing `" + key + "` in " + m));
    }

    private static @Nullable Integer optionalNumber(Map<String, Object> m, String key) {
        return switch (m.get(key)) {
            case null -> null;
            case Integer i when i > 0 -> i;
            case Object v -> throw new IllegalArgumentException("`" + key + "` must be a positive number: " + v);
        };
    }

    /** Every topic name in the catalogue, derived ones included. */
    public Stream<String> names() {
        return desired().stream().map(TopicSpec::name);
    }
}
