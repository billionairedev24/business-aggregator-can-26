package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.AutomatedVetting;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.shared.NotFound;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submit → automated vetting → approved (live, {@code listing.published}) or flagged; publish / hide; delete. Every
 * transition saves the listing and publishes its event in the same transaction (Modulith outbox).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ListingLifecycleService implements ManageListing, VetListing {

    /** Max differing bits between two average hashes to call the images duplicates. */
    static final int DUPLICATE_DISTANCE = 4;

    private final ListingRepository listings;
    private final CategoryCatalog categories;
    private final MediaRepository media;
    private final LicenceRegistry licences;
    private final ViewListing viewListing;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public ListingView submit(String merchantId, String listingId, String actorId) {
        var listing = load(merchantId, listingId);
        var category = profile(listing.categoryId());
        var completeness = switch (listing) {
            case ProductListing p -> p.completeness(category);
            case ServiceListing s -> s.completeness(category);
        };
        var submitted = listing.submit(completeness, actorId, clock.instant());
        save(listing);
        events.publishEvent(submitted);
        return viewListing.view(merchantId, listingId);
    }

    @Override
    public void publish(String merchantId, String listingId) {
        var listing = load(merchantId, listingId);
        var event = listing.publish(clock.instant());
        save(listing);
        event.ifPresent(events::publishEvent);
    }

    @Override
    public void hide(String merchantId, String listingId) {
        var listing = load(merchantId, listingId);
        var event = listing.hide(clock.instant());
        save(listing);
        event.ifPresent(events::publishEvent);
    }

    @Override
    public void delete(String merchantId, String listingId, String actorId) {
        var listing = load(merchantId, listingId);
        listings.delete(listing);
        events.publishEvent(listing.deleted(actorId, clock.instant()));
    }

    @Override
    public void vet(String listingId) {
        var found = listings.find(listingId);
        if (found.isEmpty() || found.get().getState().getVetting() != Vetting.PENDING) {
            return; // deleted or withdrawn meanwhile, or already vetted (a retried event)
        }
        var listing = found.get();
        var category = profile(listing.categoryId());
        var registry = category == null ? null : category.regulatedRegistry();
        var licenceOk = registry == null || licences.hasVerifiedLicence(listing.getMerchantId(), registry);
        var duplicate = false;
        var mainOnWhite = true;
        if (listing instanceof ProductListing p && p.getDetails().imageSource() == ImageSource.OWN) {
            var own = media.findAll(p.getDetails().ownImageIds());
            for (var image : own) {
                var hash = image.phash();
                if (hash != null && media.hasNearDuplicate(hash, listing.getMerchantId(), DUPLICATE_DISTANCE)) {
                    duplicate = true;
                    break;
                }
            }
            mainOnWhite = own.isEmpty() || own.getFirst().onWhite();
        }
        var flags = AutomatedVetting.check(new AutomatedVetting.Subject(
                listing.kind(), category, listing.priceCents(), licenceOk, duplicate, mainOnWhite));
        var outcome = listing.vetted(flags, clock.instant());
        save(listing);
        outcome.ifPresent(events::publishEvent);
        log.debug("Vetted listing {}: {}", listingId, flags.isEmpty() ? "approved" : flags);
    }

    private Listing load(String merchantId, String listingId) {
        return listings.find(merchantId, listingId).orElseThrow(() -> new NotFound("listing", listingId));
    }

    private void save(Listing listing) {
        switch (listing) {
            case ProductListing p -> listings.save(p);
            case ServiceListing s -> listings.save(s);
        }
    }

    private @Nullable CategoryProfile profile(@Nullable String categoryId) {
        return categoryId == null ? null : categories.profile(categoryId).orElse(null);
    }
}
