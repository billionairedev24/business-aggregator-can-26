package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.ListingStatus;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.catalogue.domain.VettingFlag;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of the listings table.
 *
 * @param categoryName localised leaf name
 * @param pricingMode services only
 * @param stock products only (null for services)
 * @param revetReasons S-39: why an approved listing is back in vetting (empty otherwise)
 * @param bundle S-65: a bundle of the business's products (its stock is what the items allow)
 */
public record ListingSummary(
        String id,
        ListingKind kind,
        String name,
        @Nullable String sku,
        @Nullable Long priceCents,
        @Nullable Integer stock,
        int sales30d,
        Vetting vetting,
        ListingStatus status,
        List<VettingFlag> flags,
        List<MaterialField> revetReasons,
        @Nullable Instant submittedAt,
        @Nullable String categoryId,
        @Nullable String categoryName,
        @Nullable PricingMode pricingMode,
        @Nullable Integer durationMin,
        boolean instantBook,
        Instant updatedAt,
        boolean bundle) {

    public ListingSummary {
        flags = List.copyOf(flags);
        revetReasons = List.copyOf(revetReasons);
    }
}
