package ca.northline.catalogue.application;

import ca.northline.catalogue.api.ListingPublished;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.merchants.api.RestrictedLicences;
import ca.northline.region.api.AgeClass;
import ca.northline.shared.Conflict;
import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Age-restricted listings (2026-10-04): a product whose category carries an age class goes live only while its business
 * holds an approved licence for that class in its province. Publishing without one is 409 {@code licence_required};
 * a listing that vetting (or a reviewer) approves without one stays hidden, held for the licence ({@link LicenceHolds}),
 * and goes live when the licence is approved ({@code RestrictedLicenceChanged}).
 */
@Component
@RequiredArgsConstructor
class LicenceGuard {

    private final CategoryCatalog categories;
    private final RestrictedLicences licences;
    private final LicenceHolds holds;

    @Nullable
    AgeClass ageClass(Listing listing) {
        if (!(listing instanceof ProductListing) || listing.categoryId() == null) {
            return null;
        }
        return categories
                .profile(listing.categoryId())
                .map(CategoryProfile::ageClass)
                .orElse(null);
    }

    boolean licensed(Listing listing) {
        var c = ageClass(listing);
        return c == null || licences.licensed(listing.getMerchantId(), c);
    }

    void requirePublishable(Listing listing) {
        if (!licensed(listing)) {
            throw new Conflict("licence_required", ListingMessages.LICENCE_REQUIRED);
        }
    }

    /**
     * After vetting or a reviewer approved the listing (before it is saved): when that made it visible without the
     * licence, it is hidden again and nothing is published; {@link #holdIfHidden} marks it once saved.
     */
    Optional<? extends DomainEvent> gate(Listing listing, Optional<? extends DomainEvent> outcome, Instant at) {
        if (outcome.isPresent() && outcome.get() instanceof ListingPublished && !licensed(listing)) {
            listing.hide(at);
            return Optional.empty();
        }
        return outcome;
    }

    /** The seller hid or published it themselves: the platform no longer holds it. */
    void release(Listing listing) {
        if (listing instanceof ProductListing) {
            holds.hold(listing.getId(), false);
        }
    }

    /** Marks a restricted listing the platform hid (call after saving it). */
    void holdIfHidden(Listing listing) {
        if (ageClass(listing) != null
                && !listing.getState().isCustomerVisible()
                && !licensed(listing)
                && listing.getState().getVetting() == Vetting.APPROVED) {
            holds.hold(listing.getId(), true);
        }
    }
}
