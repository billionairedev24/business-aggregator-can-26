package ca.northline.catalogue.domain;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A merchant's offer on a catalogue record ({@code catalogue.offers} + {@code variants}), aggregate root. The record
 * holds the product content; the offer holds the seller's price, stock, fulfilment, compliance and own images.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public final class ProductListing implements Listing {
    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private ProductDetails details;
    /** The catalogue record this offer is attached to (shared GTIN record or the seller's own). */
    private CatalogRecord record;

    private final ListingState state;

    public static ProductListing create(
            String id, String merchantId, ProductDetails details, CatalogRecord record, Instant at) {
        return new ProductListing(id, merchantId, details, record, ListingState.draft(at));
    }

    /** Editor save. A pending listing goes back to draft. */
    public void revise(ProductDetails newDetails, CatalogRecord newRecord, Instant at) {
        details = newDetails;
        record = newRecord;
        state.edited(at);
    }

    /** Bulk quick update (price & stock template, integration sync): no content change, vetting unaffected. */
    public void restock(long newPriceCents, int newStock, Instant at) {
        details = details.withPriceAndStock(newPriceCents, newStock);
        state.touched(at);
    }

    public Completeness completeness(@Nullable CategoryProfile category) {
        return details.completeness(category, catalogueImageIds());
    }

    /** Images of the shared catalogue record the offer can inherit (none for a seller-owned record). */
    public java.util.List<String> catalogueImageIds() {
        return record.isSellerOwned() ? java.util.List.of() : record.imageIds();
    }

    /** Is the offer's content shared with (and owned by) someone else, so identity fields are read-only? */
    public boolean contentShared() {
        return !record.editableBy(merchantId);
    }

    @Override
    public ListingKind kind() {
        return ListingKind.PRODUCT;
    }

    @Override
    public String displayName() {
        return details.title();
    }

    @Override
    public @Nullable String sku() {
        return details.sku();
    }

    @Override
    public Long priceCents() {
        return details.priceCents();
    }

    @Override
    public @Nullable String categoryId() {
        return details.categoryId();
    }
}
