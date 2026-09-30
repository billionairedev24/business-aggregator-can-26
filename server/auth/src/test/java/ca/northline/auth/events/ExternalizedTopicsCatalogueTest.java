package ca.northline.auth.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * S-25 / S-28: northline-auth's {@code @Externalized} topics are in the catalogue {@code deploy/kafka/topics.yaml} (the
 * provisioning Job creates them; nothing is auto-created), and every event has its JSON schema next to the api's.
 */
class ExternalizedTopicsCatalogueTest {

    private static final Path REPO = Path.of("../..");

    @SuppressWarnings("unchecked")
    @Test
    void everyExternalizedTopicIsCatalogued_andEveryEventHasASchema() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Externalized.class));
        var topics = new TreeMap<String, String>();
        for (var candidate : scanner.findCandidateComponents("ca.northline.auth")) {
            var annotation = Class.forName(candidate.getBeanClassName()).getAnnotation(Externalized.class);
            topics.put(candidate.getBeanClassName(), annotation.value().split("::", 2)[0]);
        }
        assertThat(topics).containsEntry("ca.northline.auth.application.UserRegistered", "identity.user");

        Map<String, Object> root;
        try (var in = Files.newInputStream(REPO.resolve("deploy/kafka/topics.yaml"))) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        var catalogued = new TreeSet<String>();
        ((List<Map<String, Object>>) root.get("topics")).forEach(t -> catalogued.add(String.valueOf(t.get("name"))));
        assertThat(catalogued).containsAll(topics.values());

        assertThat(REPO.resolve("server/api/src/main/resources/events/identity.user_registered.v1.schema.json"))
                .exists();
    }
}
