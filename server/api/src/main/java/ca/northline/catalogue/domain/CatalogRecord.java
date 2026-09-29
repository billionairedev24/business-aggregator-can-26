package ca.northline.catalogue.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code catalogue.catalog_products}: the product content behind offers. Standard goods have one shared record per
 * GTIN — the first seller creates it and uploads the images, later sellers attach an offer and inherit title,
 * attributes and images; a verified brand owner can lock it. Local goods get a seller-owned record.
 *
 * @param ownerMerchantId set for seller-owned records (no GTIN)
 * @param createdByMerchantId the first seller of a shared record
 * @param sellerCount offers attached to this record, across merchants
 */
public record CatalogRecord(
        String id,
        String ref,
        @Nullable String gtin,
        IdentifierType identifierType,
        @Nullable String brand,
        String title,
        @Nullable String mpn,
        @Nullable String categoryId,
        Map<String, String> attributes,
        @Nullable String description,
        List<String> bullets,
        List<String> imageIds,
        @Nullable String ownerMerchantId,
        @Nullable String createdByMerchantId,
        boolean locked,
        int sellerCount) {

    public CatalogRecord {
        attributes = Map.copyOf(attributes);
        bullets = List.copyOf(bullets);
        imageIds = List.copyOf(imageIds);
    }

    /** May {@code merchantId} change the shared content (title, attributes, images)? */
    public boolean editableBy(String merchantId) {
        return !locked && (merchantId.equals(ownerMerchantId) || merchantId.equals(createdByMerchantId));
    }

    public boolean isSellerOwned() {
        return ownerMerchantId != null;
    }

    /** The same record with content taken from a seller's details (first seller / own record edits). */
    public CatalogRecord withContent(ProductDetails d) {
        return new CatalogRecord(
                id,
                ref,
                d.gtin(),
                d.identifierType(),
                d.brand(),
                d.title(),
                d.mpn(),
                d.categoryId(),
                d.attributes(),
                d.description(),
                d.bullets(),
                d.ownImageIds().isEmpty() ? imageIds : d.ownImageIds(),
                ownerMerchantId,
                createdByMerchantId,
                locked,
                sellerCount);
    }
}
