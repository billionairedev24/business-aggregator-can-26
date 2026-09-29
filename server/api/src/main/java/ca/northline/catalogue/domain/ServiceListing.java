package ca.northline.catalogue.domain;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/** A bookable service ({@code catalogue.services}), aggregate root. */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public final class ServiceListing implements Listing {
    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private ServiceDetails details;
    private final ListingState state;

    public static ServiceListing create(String id, String merchantId, ServiceDetails details, Instant at) {
        return new ServiceListing(id, merchantId, details, ListingState.draft(at));
    }

    /** Editor save. A pending listing goes back to draft. */
    public void revise(ServiceDetails newDetails, Instant at) {
        details = newDetails;
        state.edited(at);
    }

    /** Bulk quick update of the price only; vetting unaffected. */
    public void reprice(@Nullable Long newPriceCents, Instant at) {
        details = new ServiceDetails(
                details.name(),
                details.categoryId(),
                details.pricingMode(),
                newPriceCents,
                details.durationMin(),
                details.bufferMin(),
                details.included(),
                details.instantBook(),
                details.sku());
        state.touched(at);
    }

    public Completeness completeness(@Nullable CategoryProfile category) {
        return details.completeness(category);
    }

    @Override
    public ListingKind kind() {
        return ListingKind.SERVICE;
    }

    @Override
    public String displayName() {
        return details.name();
    }

    @Override
    public @Nullable String sku() {
        return details.sku();
    }

    @Override
    public @Nullable Long priceCents() {
        return details.priceCents();
    }

    @Override
    public @Nullable String categoryId() {
        return details.categoryId();
    }
}
