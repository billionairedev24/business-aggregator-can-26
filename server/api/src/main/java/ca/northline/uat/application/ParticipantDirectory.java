package ca.northline.uat.application;

import ca.northline.merchants.api.PilotCohort;
import ca.northline.uat.application.UatStore.Participant;
import ca.northline.uat.domain.Persona;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who takes part in UAT. Businesses are S-120's pilot cohort ({@link PilotCohort}): every pilot business that has a
 * business on Northline (its invite accepted, or enrolled) takes part with the persona of its type — a provider-and-
 * seller business as both — under the pilot's id and working name. People (customers, couriers, staff), whom S-120
 * doesn't know, are {@code uat.participants}. A pilot business stays in UAT while it is in the cohort.
 */
@Component
@RequiredArgsConstructor
class ParticipantDirectory {

    private final UatStore store;
    private final PilotCohort cohort;

    /** Everyone: people (active or not) and the cohort's businesses. */
    List<Participant> all() {
        return Stream.concat(store.participants().stream(), businesses().stream())
                .toList();
    }

    /** The participant rows of an id: one for a person, one per persona for a business. */
    List<Participant> byId(String id) {
        var person = store.participant(id);
        if (person.isPresent()) {
            return List.of(person.get());
        }
        return businesses().stream().filter(p -> p.id().equals(id)).toList();
    }

    /** The person's active participation, and that of the businesses given (the caller checked membership). */
    List<Participant> activeFor(String userId, Collection<String> merchantIds) {
        var people = store.activeFor(userId);
        if (merchantIds.isEmpty()) {
            return people;
        }
        var theirs = businesses().stream()
                .filter(p -> p.merchantId() != null && merchantIds.contains(p.merchantId()))
                .toList();
        return Stream.concat(theirs.stream(), people.stream()).toList();
    }

    private List<Participant> businesses() {
        return cohort.list(null).stream()
                .filter(p -> p.merchantId() != null)
                .flatMap(p -> personas(p.businessType()).stream()
                        .map(persona -> new Participant(
                                p.id(), null, p.merchantId(), persona, p.label(), true, "pilot-cohort", p.createdAt())))
                .toList();
    }

    static List<Persona> personas(String businessType) {
        return switch (businessType) {
            case "provider" -> List.of(Persona.PROVIDER);
            case "seller" -> List.of(Persona.SELLER);
            case "kitchen" -> List.of(Persona.KITCHEN);
            case "both" -> List.of(Persona.PROVIDER, Persona.SELLER);
            default -> List.of();
        };
    }
}
