package ca.northline.worker.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.worker.events.PoisonEventException;
import ca.northline.worker.topics.TopicCatalogue.TopicSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/** The catalogue is consistent, fits every managed Kafka, and scripts/topics.sh + the worker's listeners agree with it. */
class TopicCatalogueTest {

    private static final Path REPO = Path.of(System.getProperty("northline.repo", "../.."));

    private final TopicCatalogue catalogue = TopicCatalogue.fromClasspath();

    @Test
    void derivesEveryTopicItsDlqAndTheConsumersRetryTopics() {
        var names = catalogue.names().toList();
        assertThat(names).doesNotHaveDuplicates();
        for (var topic : catalogue.topics()) {
            assertThat(names).contains(topic.name(), topic.name() + ".dlq");
        }
        assertThat(names)
                .contains(
                        "catalogue.listing.search-indexer.retry-0",
                        "catalogue.listing.search-indexer.retry-1",
                        "catalogue.listing.search-indexer.retry-2")
                .doesNotContain("catalogue.listing.search-indexer.retry-3");
        var retry = spec("food.menu.search-indexer.retry-0");
        assertThat(retry.partitions()).isEqualTo(spec("food.menu").partitions());
        assertThat(retry.configs()).containsEntry("retention.ms", "86400000");
        assertThat(spec("payments.payout.dlq").configs())
                .containsEntry("retention.ms", String.valueOf(30L * 24 * 3_600_000))
                .containsEntry("cleanup.policy", "delete");
        assertThat(spec("payments.payout").partitions()).isEqualTo(6);
    }

    @Test
    void fitsAzureEventHubsPremiumOnOneProcessingUnit() {
        // Event Hubs Premium: 100 event hubs per processing unit (dev and staging run 1 PU, infrastructure.md § 5).
        // More topics mean more PUs in every Azure environment — or fewer retry delays per consumer.
        assertThat(catalogue.desired()).hasSizeLessThanOrEqualTo(100);
        // Event hub names: letters, digits, '.', '-', '_'; start and end with a letter or digit; ≤ 256 characters.
        assertThat(catalogue.names())
                .allMatch(n -> n.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}[A-Za-z0-9]"), "valid event hub names");
        // Event Hubs Premium keeps events at most 90 days.
        assertThat(catalogue.desired())
                .allMatch(s -> Long.parseLong(s.configs().get("retention.ms")) <= 90L * 24 * 3_600_000);
    }

    @Test
    void scriptsTopicsShDerivesTheSameTopicsAsTheProvisioner() throws Exception {
        var process = new ProcessBuilder("sh", REPO.resolve("scripts/topics.sh").toString(), "--list")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();

        var expected = catalogue.desired().stream()
                .map(s -> "%s %d %s %s"
                        .formatted(
                                s.name(),
                                s.partitions(),
                                s.configs().get("retention.ms"),
                                s.configs().get("cleanup.policy")))
                .toList();
        assertThat(output.lines().toList()).containsExactlyElementsOf(expected);
    }

    @Test
    void everyConsumerPolicyMatchesItsRetryableListener() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        var seen = new HashSet<String>();
        for (var candidate : scanner.findCandidateComponents("ca.northline.worker")) {
            var type = Class.forName(candidate.getBeanClassName());
            for (var method : type.getDeclaredMethods()) {
                var listener = AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener.class);
                var retry = AnnotatedElementUtils.findMergedAnnotation(method, RetryableTopic.class);
                if (listener == null) {
                    continue;
                }
                assertThat(retry)
                        .as("%s needs @RetryableTopic (CLAUDE.md § Events)", method)
                        .isNotNull();
                var consumer = catalogue
                        .consumer(listener.groupId())
                        .orElseThrow(() -> new AssertionError(
                                "consumer group " + listener.groupId() + " is not in deploy/kafka/topics.yaml"));
                seen.add(consumer.group());
                assertThat(listener.topics()).containsExactlyInAnyOrderElementsOf(consumer.topics());
                assertThat(Integer.parseInt(retry.attempts())).isEqualTo(consumer.attempts());
                assertThat(retry.retryTopicSuffix()).isEqualTo(consumer.retryTopicSuffix());
                assertThat(retry.dltTopicSuffix()).isEqualTo(TopicCatalogue.DLQ_SUFFIX);
                assertThat(retry.topicSuffixingStrategy()).isEqualTo(TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE);
                assertThat(retry.autoCreateTopics()).isEqualTo("false");
                assertThat(delaysSeconds(retry)).containsExactlyElementsOf(consumer.retryDelaysSeconds());
                // A fixed back-off makes Spring name a single retry topic without "-0": keep it exponential.
                assertThat(multiplier(retry)).as("%s multiplier", method).isGreaterThan(1);
                // S-26: poison records skip the retries; every consumer answers for its own DLQ records.
                assertThat(retry.exclude()).contains(PoisonEventException.class);
                assertThat(retry.traversingCauses()).isEqualTo("true");
                assertThat(Arrays.stream(type.getDeclaredMethods())
                                .anyMatch(m -> m.isAnnotationPresent(DltHandler.class)))
                        .as("%s needs a @DltHandler calling EventProcessing.deadLettered", type)
                        .isTrue();
            }
        }
        assertThat(seen)
                .as("every consumer in the catalogue has a listener")
                .containsExactlyInAnyOrderElementsOf(catalogue.consumers().stream()
                        .map(TopicCatalogue.Consumer::group)
                        .toList());
    }

    @Test
    void rejectsInconsistentCatalogues() {
        var base = """
                defaults: { partitions: 6, retentionHours: 168, cleanupPolicy: delete }
                dlq: { partitions: 1, retentionHours: 720 }
                retry: { retentionHours: 24 }
                """;
        assertThatThrownBy(() -> load(base + """
                        topics:
                          - { name: a.b, owner: a }
                          - { name: a.b, owner: a }
                        """)).hasMessageContaining("listed twice: a.b");
        assertThatThrownBy(() -> load(base + """
                        topics:
                          - { name: a.b, owner: a }
                        consumers:
                          - { group: g, topics: [a.c], retryDelaysSeconds: [1] }
                        """)).hasMessageContaining("reads unknown topic a.c");
        assertThatThrownBy(() -> load(base + "topics:\n  - { name: Payments, owner: a }\n"))
                .hasMessageContaining("<module>.<aggregate>");
        assertThatThrownBy(() -> load(base + "topics:\n  - { name: a.b, owner: a, partitions: 0 }\n"))
                .hasMessageContaining("positive");
    }

    private static TopicCatalogue load(String yaml) {
        return TopicCatalogue.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private TopicSpec spec(String name) {
        return catalogue.desired().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The delays Spring derives from {@code @BackOff}: delay × multiplier^n, capped at maxDelay. A {@code *String}
     * attribute (a property, so tests can shorten it) counts with its default ({@code ${name:default}}).
     */
    private static List<Integer> delaysSeconds(RetryableTopic retry) {
        var backOff = retry.backOff();
        var delays = new ArrayList<Integer>();
        var delay = value(backOff.delayString(), backOff.delay());
        var multiplier = value(backOff.multiplierString(), backOff.multiplier());
        var max = value(backOff.maxDelayString(), backOff.maxDelay());
        for (var i = 0; i < Integer.parseInt(retry.attempts()) - 1; i++) {
            var capped = max > 0 ? Math.min(delay, max) : delay;
            delays.add((int) (capped / 1000));
            delay *= multiplier > 0 ? multiplier : 1;
        }
        return delays;
    }

    private static double multiplier(RetryableTopic retry) {
        return value(retry.backOff().multiplierString(), retry.backOff().multiplier());
    }

    private static double value(String text, double fallback) {
        if (text.isBlank()) {
            return fallback;
        }
        var m = java.util.regex.Pattern.compile("\\$\\{[^:}]+:([^}]+)}").matcher(text);
        return Double.parseDouble(m.matches() ? m.group(1) : text);
    }

    @Test
    void consumerHelpers() {
        var consumer = new TopicCatalogue.Consumer("notifications", List.of("a.b"), List.of(10, 60));
        assertThat(consumer.attempts()).isEqualTo(3);
        assertThat(consumer.retryTopicSuffix()).isEqualTo(".notifications.retry");
    }
}
