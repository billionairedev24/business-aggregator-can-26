package ca.northline.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.modulith.events.Externalized;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * S-25: every {@code @Externalized("<topic>::…")} target is in the topic catalogue {@code deploy/kafka/topics.yaml}, so
 * the provisioning Job creates it (and its {@code .dlq}) before the api publishes to it. Topics are never auto-created
 * in any environment: a missing one makes the externalizer fail and the outbox retry forever.
 */
public class ExternalizedTopicsCatalogueTest {

    private static final Path CATALOGUE = Path.of("../../deploy/kafka/topics.yaml");

    @Test
    void everyExternalizedTopicIsInTheCatalogue() throws Exception {
        var externalized = externalizedTopics();
        assertThat(externalized).as("@Externalized events found").hasSizeGreaterThan(20);

        var catalogued = catalogueTopics();
        var missing = new TreeMap<String, String>();
        externalized.forEach((event, topic) -> {
            if (!catalogued.contains(topic)) {
                missing.put(event, topic);
            }
        });
        assertThat(missing)
                .as("add these topics to deploy/kafka/topics.yaml (docs/runbooks/infrastructure.md § 5.3)")
                .isEmpty();
    }

    @Test
    void topicsFollowModuleAggregateNaming() {
        assertThat(externalizedTopics().values()).allMatch(t -> t.matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*"));
    }

    /** event class → topic, from {@code @Externalized("topic::key")} on every class under ca.northline. */
    public static Map<String, String> externalizedTopics() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true; // records nested in sealed interfaces included
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Externalized.class));
        var topics = new TreeMap<String, String>();
        for (var candidate : scanner.findCandidateComponents("ca.northline")) {
            var type = candidate.getBeanClassName();
            try {
                var annotation = Class.forName(type).getAnnotation(Externalized.class);
                var target = annotation.value().isBlank() ? annotation.target() : annotation.value();
                topics.put(type, target.split("::", 2)[0]);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return topics;
    }

    @SuppressWarnings("unchecked")
    static Set<String> catalogueTopics() throws IOException {
        try (var in = Files.newInputStream(CATALOGUE)) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            var names = new TreeSet<String>();
            ((List<Map<String, Object>>) root.get("topics")).forEach(t -> names.add(String.valueOf(t.get("name"))));
            return names;
        }
    }
}
