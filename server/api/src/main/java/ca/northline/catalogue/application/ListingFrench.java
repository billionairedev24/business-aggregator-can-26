package ca.northline.catalogue.application;

import ca.northline.region.api.FrenchListings;
import org.jspecify.annotations.Nullable;

/**
 * S-116 (Loi 96 readiness): a listing's French name and description, and what the merchant's place asks of them — the
 * region configuration's {@code french_listings} for the business's market or province (never a place in code).
 */
public interface ListingFrench {

    /** The listing's French text and the rule; a listing without French yet has empty fields. */
    FrenchText view(String merchantId, String listingId);

    /** Saves the French text (owners and technicians); validated like the listing's own name and description. */
    FrenchText save(String merchantId, String listingId, String title, @Nullable String description, String actorId);

    /** Throws a 422 {@code french} when the place requires French text and the listing lacks it (submit, publish). */
    void checkBeforeLive(String merchantId, String listingId);

    /**
     * @param rule what the merchant's place asks: off, warn (the Studio shows {@code missing}), require (and submit /
     *     publish are refused while {@code missing})
     * @param missing the French name or description isn't written yet
     */
    record FrenchText(
            FrenchListings rule, String title, @Nullable String description, boolean missing) {}
}
