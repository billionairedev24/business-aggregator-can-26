package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.merchants.api.RestrictedLicenceChanged;
import ca.northline.region.api.AgeClass;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A business's licence for an age class ended (expired, replaced with none) or came into force: its live listings of
 * the class are hidden and held ({@code listing.hidden}, search drops them), or the held ones go live again ({@code
 * listing.published}). Only what the platform hid comes back — a listing the seller hid stays hidden. Idempotent: a
 * retried event finds nothing left to move.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RestrictedListingVisibility {

    private final LicenceHolds holds;
    private final ListingRepository listings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @ApplicationModuleListener
    void on(RestrictedLicenceChanged event) {
        var ageClass = AgeClass.of(event.ageClass()).orElse(null);
        if (ageClass == null) {
            return;
        }
        var at = clock.instant();
        if (event.licensed()) {
            for (var id : holds.heldOffers(event.aggregateId(), ageClass)) {
                listings.find(event.aggregateId(), id).ifPresent(listing -> {
                    var published = listing.publish(at);
                    save(listing);
                    holds.hold(id, false);
                    published.ifPresent(events::publishEvent);
                });
            }
        } else {
            for (var id : holds.liveOffers(event.aggregateId(), ageClass)) {
                listings.find(event.aggregateId(), id).ifPresent(listing -> {
                    var hidden = listing.hide(at);
                    save(listing);
                    holds.hold(id, true);
                    hidden.ifPresent(events::publishEvent);
                });
            }
        }
        log.info(
                "Licence for {} of {} {}: restricted listings updated",
                event.ageClass(),
                event.aggregateId(),
                event.licensed() ? "in force" : "ended");
    }

    private void save(Listing listing) {
        if (listing instanceof ProductListing p) {
            listings.save(p);
        }
    }
}
