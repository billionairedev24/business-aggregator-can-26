package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port for listing aggregates (offers + variants, services). */
public interface ListingRepository {

    Optional<ProductListing> product(String merchantId, String listingId);

    Optional<ServiceListing> service(String merchantId, String listingId);

    /** Either kind, scoped to the merchant. */
    Optional<Listing> find(String merchantId, String listingId);

    /** Either kind, any merchant (vetting runs outside a request). */
    Optional<Listing> find(String listingId);

    Optional<Listing> bySku(String merchantId, String sku);

    boolean skuTaken(String merchantId, String sku, @Nullable String exceptListingId);

    void save(ProductListing listing);

    void save(ServiceListing listing);

    void delete(Listing listing);
}
