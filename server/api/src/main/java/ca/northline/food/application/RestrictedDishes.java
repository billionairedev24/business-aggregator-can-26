package ca.northline.food.application;

import ca.northline.merchants.api.RestrictedLicenceChanged;
import ca.northline.region.api.AgeClass;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A kitchen's licence for an age class ended or came into force: its age-restricted dishes leave the menu (back to
 * draft, held for the licence) or come back. Idempotent: a retried event finds nothing left to move.
 */
@Component
@RequiredArgsConstructor
class RestrictedDishes {

    private final MenuBuilderService menus;

    @ApplicationModuleListener
    void on(RestrictedLicenceChanged event) {
        AgeClass.of(event.ageClass()).ifPresent(c -> menus.licenceChanged(event.aggregateId(), c, event.licensed()));
    }
}
