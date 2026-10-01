package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.OfferType;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.Vetting;
import java.util.Collection;
import java.util.List;
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

    /** S-65: the merchant's offers among {@code offerIds}, as a bundle's rules and editor need them; others skipped. */
    List<BundleComponent> bundleComponents(String merchantId, Collection<String> offerIds);

    /** S-65: is the offer an item of any bundle? */
    boolean inBundle(String offerId);

    /** One offer a bundle may name: its type, vetting, price and stock, and its variants when it has some. */
    record BundleComponent(
            String offerId,
            String name,
            OfferType type,
            Vetting vetting,
            long priceCents,
            int stock,
            List<VariantFact> variants) {
        public BundleComponent {
            variants = List.copyOf(variants);
        }
    }

    record VariantFact(String id, String value, long priceCents, int stock) {}
}
