package ca.northline.catalogue.domain;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /**
     * Editor save. A pending listing goes back to draft; an approved one whose price, category or images changed goes
     * back to vetting (S-39). @return the events to publish
     */
    public List<DomainEvent> revise(ProductDetails newDetails, CatalogRecord newRecord, String actorId, Instant at) {
        var before = snapshot();
        details = newDetails;
        record = newRecord;
        state.edited(at);
        return revetIfMaterial(MaterialField.between(before, snapshot()), actorId, at);
    }

    /**
     * Bulk quick update (price &amp; stock template): a new price on an approved listing sends it back to vetting (S-39),
     * stock alone doesn't. @return the events to publish
     */
    public List<DomainEvent> restock(long newPriceCents, int newStock, String actorId, Instant at) {
        var before = snapshot();
        details = details.withPriceAndStock(newPriceCents, newStock);
        state.touched(at);
        return revetIfMaterial(MaterialField.between(before, snapshot()), actorId, at);
    }

    /**
     * Price and stock from a connected platform (S-35), which is their source of truth. A price change on an approved
     * listing still sends it back to vetting (S-39); stock alone doesn't. @return empty when nothing changed, else the
     * events to publish (possibly none)
     */
    public Optional<List<DomainEvent>> syncStock(
            java.util.Map<String, ProductDetails.PriceStock> bySku, String actorId, Instant at) {
        var synced = details.withSyncedStock(bySku);
        if (synced.equals(details)) {
            return Optional.empty();
        }
        var before = snapshot();
        details = synced;
        state.touched(at);
        return Optional.of(revetIfMaterial(MaterialField.between(before, snapshot()), actorId, at));
    }

    /**
     * What vetting and customers look at (S-39): every price (offer and variants by SKU), the category, and the images
     * customers see, in order.
     */
    public Snapshot snapshot() {
        var prices = new ArrayList<String>();
        prices.add("offer:" + details.priceCents());
        details.variants().forEach(v -> prices.add(v.sku() + ":" + v.priceCents()));
        var images = new ArrayList<String>();
        images.add(details.imageSource().code());
        if (details.imageSource() == ImageSource.SHARED) {
            images.addAll(catalogueImageIds());
        }
        images.addAll(details.ownImageIds());
        return new Snapshot(prices, details.categoryId(), images);
    }

    public record Snapshot(List<String> prices, @Nullable String categoryId, List<String> images) {
        public Snapshot {
            prices = List.copyOf(prices);
            images = List.copyOf(images);
        }
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
