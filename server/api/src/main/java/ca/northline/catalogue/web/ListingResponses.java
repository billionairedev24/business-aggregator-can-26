package ca.northline.catalogue.web;

import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.HandlingTime;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.ListingStatus;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.OfferType;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ReturnsPolicy;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.catalogue.domain.VettingFlag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Response bodies of the listing endpoints. Enums serialise as lower-case codes, money as cents. */
final class ListingResponses {
    private ListingResponses() {}

    /**
     * {@code GET /listings} row (contract: id, kind, name, sku, meta, priceCents, stock, sales30d, vetting, status;
     * the rest is additive).
     */
    record ListingItem(
            String id,
            ListingKind kind,
            String name,
            @Nullable String sku,
            String meta,
            @Nullable Long priceCents,
            @Nullable Integer stock,
            int sales30d,
            Vetting vetting,
            ListingStatus status,
            List<VettingFlag> vettingFlags,
            List<MaterialField> revetReasons,
            @Nullable Instant submittedAt,
            @Nullable String categoryId,
            @Nullable PricingMode pricingMode,
            Instant updatedAt,
            boolean bundle) {}

    /** The editor's view of one listing — {@link ProductResponse} or {@link ServiceResponse}, told apart by kind. */
    sealed interface ListingDetail permits ProductResponse, ServiceResponse {}

    record MediaResponse(String id, String url, int width, int height, boolean onWhite) {}

    record VariantResponse(
            @Nullable String id,
            String value,
            String sku,
            @Nullable String gtin,
            long priceCents,
            int stock,
            List<MediaResponse> images) {}

    /** S-65: one item of a bundle, with the product's name, variant, own price and stock. */
    record BundleItemResponse(
            String offerId,
            @Nullable String variantId,
            int qty,
            String name,
            @Nullable String option,
            long unitPriceCents,
            int stock) {}

    record MissingField(String field, String message) {}

    /** Completeness meter: done / total sections, and what blocks "Submit for vetting". */
    record CompletenessResponse(int percent, int done, int total, List<MissingField> missing) {}

    record ProductResponse(
            String id,
            String kind,
            Vetting vetting,
            ListingStatus status,
            List<VettingFlag> vettingFlags,
            List<MaterialField> revetReasons,
            @Nullable Instant submittedAt,
            Instant updatedAt,
            // catalogue record
            String catalogRef,
            String catalogTitle,
            boolean sharedRecord,
            boolean contentShared,
            boolean contentLocked,
            int sellerCount,
            // identity & category
            IdentifierType identifierType,
            @Nullable String gtin,
            String title,
            @Nullable String brand,
            @Nullable String mpn,
            @Nullable String categoryId,
            Map<String, String> attributes,
            @Nullable String description,
            List<String> bullets,
            // variants
            VariantTheme variantTheme,
            List<VariantResponse> variants,
            // images
            ImageSource imageSource,
            List<MediaResponse> images,
            List<MediaResponse> catalogueImages,
            // price, stock & fulfilment
            @Nullable String sku,
            long priceCents,
            @Nullable Long compareAtCents,
            @Nullable Long costCents,
            ItemCondition condition,
            int stock,
            @Nullable Integer lowStockAt,
            List<Fulfilment> fulfilment,
            @Nullable HandlingTime handlingTime,
            @Nullable ReturnsPolicy returnsPolicy,
            // compliance
            @Nullable String countryOfOrigin,
            boolean restrictedOk,
            boolean bilingualOk,
            boolean warranty,
            @Nullable String searchKeywords,
            CompletenessResponse completeness,
            // bundle (S-65): stock above is what the items allow
            OfferType type,
            List<BundleItemResponse> bundleItems)
            implements ListingDetail {}

    record ServiceResponse(
            String id,
            String kind,
            Vetting vetting,
            ListingStatus status,
            List<VettingFlag> vettingFlags,
            List<MaterialField> revetReasons,
            @Nullable Instant submittedAt,
            Instant updatedAt,
            String name,
            @Nullable String categoryId,
            PricingMode pricingMode,
            @Nullable Long priceCents,
            int durationMin,
            int bufferMin,
            @Nullable String included,
            boolean instantBook,
            @Nullable String sku,
            CompletenessResponse completeness)
            implements ListingDetail {}

    /** {@code GET /catalogue/categories} item. */
    record CategoryResponse(
            String id,
            @Nullable String parentId,
            String name,
            boolean leaf,
            @Nullable String regulatedRegistry,
            boolean banned,
            boolean perishable,
            List<AttributeResponse> attributes,
            List<VariantTheme> variantThemes,
            @Nullable Long medianPriceCents) {}

    record AttributeResponse(String key, String label, List<String> options, boolean required) {}

    /** {@code GET /catalogue/products/lookup?gtin=} — the shared record a GTIN matched. */
    record CatalogMatchResponse(
            String id,
            String ref,
            @Nullable String gtin,
            @Nullable String brand,
            String title,
            @Nullable String mpn,
            @Nullable String categoryId,
            Map<String, String> attributes,
            @Nullable String description,
            List<String> bullets,
            List<MediaResponse> images,
            int sellerCount,
            boolean locked) {}
}
