package ca.northline.contracts;

import ca.northline.platform.EventHeaders;
import ca.northline.shared.DomainEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.modulith.events.Externalized;

/**
 * Every {@code @Externalized} event on the classpath under {@code ca.northline} — the api's modules and northline-auth
 * (records nested in sealed interfaces included) — with its wire type ({@link EventHeaders#type}, the producers' shared
 * rule that names the schema files) and version ({@code version()} for the api's {@code DomainEvent}s, 1 otherwise).
 */
public final class PublishedEvents {

    /** One published event class. */
    public record Event(Class<?> type, String wireType, int version) {
        public String schemaFile() {
            return wireType + ".v" + version + ".schema.json";
        }

        public String name() {
            return type.getName().replace("ca.northline.", "");
        }
    }

    private PublishedEvents() {}

    /**
     * Module-internal {@code DomainEvent} records (not {@code @Externalized}) named like
     * {@code <module>.<snake_case record name>} after their module package. They may have a schema — documenting a
     * payload that could be externalized later — which is then held to checks 1 and 3 as well.
     */
    public static List<Event> internal() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isConcrete();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));
        return scanner.findCandidateComponents("ca.northline").stream()
                .map(candidate -> {
                    try {
                        return Class.forName(candidate.getBeanClassName());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .filter(type -> type.isRecord() && type.getAnnotation(Externalized.class) == null)
                .map(type -> new Event(type, moduleType(type), SamplePayloads.version(type)))
                .sorted(Comparator.comparing(Event::name))
                .toList();
    }

    private static String moduleType(Class<?> type) {
        var module = type.getPackageName().replace("ca.northline.", "").split("\\.")[0];
        return module + "."
                + type.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static List<Event> scan() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Externalized.class));
        return scanner.findCandidateComponents("ca.northline").stream()
                .map(candidate -> {
                    try {
                        var type = Class.forName(candidate.getBeanClassName());
                        return new Event(type, EventHeaders.type(type), SamplePayloads.version(type));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .sorted(Comparator.comparing(Event::name))
                .toList();
    }
}
