package ca.northline.privacy.application;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Every module's {@link PersonalDataContributor}, in erasure order. */
@Component
class Contributors {

    private final List<PersonalDataContributor> ordered;
    private final Map<String, PersonalDataContributor> byModule;
    private final Map<String, PersonalDataContributor> byField;

    Contributors(List<PersonalDataContributor> all) {
        this.ordered = all.stream()
                .sorted(Comparator.comparingInt(PersonalDataContributor::order)
                        .thenComparing(PersonalDataContributor::module))
                .toList();
        this.byModule = ordered.stream()
                .collect(Collectors.toUnmodifiableMap(PersonalDataContributor::module, Function.identity()));
        var fields = new TreeMap<String, PersonalDataContributor>();
        for (var contributor : ordered) {
            for (var field : contributor.correctable()) {
                if (fields.putIfAbsent(field, contributor) != null) {
                    throw new IllegalStateException("Two modules correct '" + field + "'");
                }
            }
        }
        this.byField = Map.copyOf(fields);
    }

    List<PersonalDataContributor> ordered() {
        return ordered;
    }

    Optional<PersonalDataContributor> module(String module) {
        return Optional.ofNullable(byModule.get(module));
    }

    Set<String> correctable() {
        return byField.keySet();
    }

    PersonalDataContributor forField(String field) {
        var contributor = byField.get(field);
        if (contributor == null) {
            throw new IllegalArgumentException("No module corrects " + field);
        }
        return contributor;
    }
}
