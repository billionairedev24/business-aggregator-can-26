package ca.northline.catalogue.domain;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
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

    /**
     * Editor save. A pending listing goes back to draft; an approved one whose price or category changed goes back to
     * vetting (S-39). @return the events to publish
     */
    public List<DomainEvent> revise(ServiceDetails newDetails, String actorId, Instant at) {
        var before = details;
        details = newDetails;
        state.edited(at);
        return revetIfMaterial(MaterialField.between(before, details), actorId, at);
    }

    /**
     * Bulk quick update of the price only: a new price on an approved listing sends it back to vetting (S-39).
     * @return the events to publish
     */
    public List<DomainEvent> reprice(@Nullable Long newPriceCents, String actorId, Instant at) {
        var before = details;
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
        return revetIfMaterial(MaterialField.between(before, details), actorId, at);
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
