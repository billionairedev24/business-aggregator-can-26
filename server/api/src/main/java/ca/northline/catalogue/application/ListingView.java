package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Completeness;
import ca.northline.catalogue.domain.MediaAsset;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Everything the editor shows for one listing. */
public sealed interface ListingView {

    Completeness completeness();

    /**
     * @param contentShared identity fields come from a shared catalogue record the merchant can't edit
     * @param catalogueImages images of the shared catalogue record (empty for seller-owned records)
     */
    record ProductView(
            ProductListing listing,
            @Nullable CategoryProfile category,
            boolean contentShared,
            List<MediaAsset> ownImages,
            List<MediaAsset> catalogueImages,
            Completeness completeness,
            List<MediaAsset> variantImages,
            List<BundleLine> bundle,
            int availableStock)
            implements ListingView {

        public ProductView {
            ownImages = List.copyOf(ownImages);
            catalogueImages = List.copyOf(catalogueImages);
            variantImages = List.copyOf(variantImages);
            bundle = List.copyOf(bundle);
        }
    }

    /**
     * S-65: one item of a bundle as the editor shows it — the product's name and variant, its own price and stock.
     *
     * @param available whole bundles this item still allows ({@code stock / qty})
     */
    record BundleLine(
            String offerId,
            @Nullable String variantId,
            int qty,
            String name,
            @Nullable String option,
            long unitPriceCents,
            int stock,
            int available) {}

    record ServiceView(ServiceListing listing, @Nullable CategoryProfile category, Completeness completeness)
            implements ListingView {}
}
