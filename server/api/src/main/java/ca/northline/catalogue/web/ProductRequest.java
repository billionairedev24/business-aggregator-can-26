package ca.northline.catalogue.web;

import static ca.northline.catalogue.domain.ListingMessages.*;

import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.HandlingTime;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.OfferType;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ReturnsPolicy;
import ca.northline.catalogue.domain.VariantTheme;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /products} and {@code PUT /products/{id}}: the product editor's draft. The contract fields for
 * onboarding are {@code gtin?, title, categoryId, priceCents, stock, variantTheme?}; everything else is optional and
 * defaults as a new draft would.
 */
record ProductRequest(
        @Nullable IdentifierType identifierType,
        @Nullable @Size(max = 20, message = GTIN_FORMAT) String gtin,

        @NotBlank(message = TITLE_REQUIRED) @Size(max = TITLE_MAX, message = TITLE_TOO_LONG)
        String title,

        @Nullable @Size(max = 80, message = TITLE_TOO_LONG) String brand,
        @Nullable @Size(max = 80, message = TITLE_TOO_LONG) String mpn,
        @NotBlank(message = CATEGORY_REQUIRED) String categoryId,
        @Nullable Map<String, String> attributes,

        @Nullable @Size(max = DESCRIPTION_MAX, message = DESCRIPTION_TOO_LONG)
        String description,

        @Nullable @Size(max = BULLETS_MAX, message = BULLETS_TOO_MANY)
        List<@Size(max = BULLET_MAX, message = BULLET_TOO_LONG) String> bullets,

        @Nullable VariantTheme variantTheme,
        @Nullable @Valid List<VariantRequest> variants,
        @Nullable ImageSource imageSource,

        @Nullable @Size(max = IMAGES_MAX, message = IMAGES_TOO_MANY)
        List<String> imageIds,

        @Nullable @Size(max = SKU_MAX, message = SKU_TOO_LONG)
        String sku,

        @NotNull(message = PRICE_REQUIRED)
        @Positive(message = PRICE_POSITIVE)
        @Max(value = PRICE_MAX_CENTS, message = PRICE_POSITIVE)
        Long priceCents,

        @Nullable @Positive(message = COMPARE_AT_HIGHER) Long compareAtCents,
        @Nullable @PositiveOrZero(message = COST_NEGATIVE) Long costCents,
        @Nullable ItemCondition condition,

        @NotNull(message = STOCK_REQUIRED) @PositiveOrZero(message = STOCK_NEGATIVE)
        Integer stock,

        @Nullable @PositiveOrZero(message = LOW_STOCK_NEGATIVE)
        Integer lowStockAt,

        @Nullable List<Fulfilment> fulfilment,
        @Nullable HandlingTime handlingTime,
        @Nullable ReturnsPolicy returnsPolicy,
        @Nullable @Size(max = 60, message = ORIGIN_REQUIRED) String countryOfOrigin,
        @Nullable Boolean restrictedOk,
        @Nullable Boolean bilingualOk,
        @Nullable Boolean warranty,

        @Nullable @Size(max = KEYWORDS_MAX, message = KEYWORDS_TOO_LONG)
        String searchKeywords,

        // S-65: a bundle of the business's own products (type bundle); ignored for products
        @Nullable OfferType type,

        @Nullable @Size(max = BUNDLE_ITEMS_MAX, message = BUNDLE_ITEMS_TOO_MANY) @Valid
        List<BundleItemRequest> bundleItems) {

    record BundleItemRequest(
            @NotBlank(message = BUNDLE_ITEM_UNKNOWN) String offerId,
            @Nullable String variantId,

            @NotNull(message = BUNDLE_QTY_RANGE)
            @Min(value = 1, message = BUNDLE_QTY_RANGE)
            @Max(value = BUNDLE_QTY_MAX, message = BUNDLE_QTY_RANGE)
            Integer qty) {}

    record VariantRequest(
            @Nullable String id,
            @NotBlank(message = VARIANT_VALUE_REQUIRED) String value,

            @NotBlank(message = SKU_REQUIRED) @Size(max = SKU_MAX, message = SKU_TOO_LONG)
            String sku,

            @Nullable @Size(max = 20, message = GTIN_FORMAT) String gtin,

            @NotNull(message = PRICE_REQUIRED) @Positive(message = PRICE_POSITIVE)
            Long priceCents,

            @NotNull(message = STOCK_REQUIRED) @PositiveOrZero(message = STOCK_NEGATIVE)
            Integer stock,

            // S-65: the variant's own images (media ids, main first); empty = it inherits the listing's
            @Nullable @Size(max = IMAGES_MAX, message = IMAGES_TOO_MANY)
            List<String> imageIds) {}

    /** Onboarding's quick form sends only a GTIN (or nothing): the identifier type follows from it. */
    ProductDetails toDetails() {
        var hasGtin = gtin != null && !gtin.isBlank();
        var idType = identifierType != null ? identifierType : hasGtin ? IdentifierType.GTIN : IdentifierType.NONE;
        var theme = Objects.requireNonNullElse(variantTheme, VariantTheme.NONE);
        return new ProductDetails(
                idType,
                idType == IdentifierType.NONE ? null : gtin,
                title,
                brand,
                mpn,
                categoryId,
                attributes == null ? Map.of() : attributes,
                description,
                bullets == null ? List.of() : bullets,
                theme,
                variants == null
                        ? List.of()
                        : variants.stream()
                                .map(v -> new ProductDetails.Variant(
                                        v.id(),
                                        v.value(),
                                        v.sku(),
                                        v.gtin(),
                                        v.priceCents(),
                                        v.stock(),
                                        v.imageIds() == null ? List.of() : v.imageIds()))
                                .toList(),
                Objects.requireNonNullElse(imageSource, hasGtin ? ImageSource.SHARED : ImageSource.OWN),
                imageIds == null ? List.of() : imageIds,
                sku,
                priceCents,
                compareAtCents,
                costCents,
                Objects.requireNonNullElse(condition, ItemCondition.NEW),
                stock,
                lowStockAt,
                fulfilment == null ? List.of(Fulfilment.POOLED) : fulfilment,
                handlingTime,
                returnsPolicy,
                countryOfOrigin == null || countryOfOrigin.isBlank() ? null : countryOfOrigin,
                Boolean.TRUE.equals(restrictedOk),
                Boolean.TRUE.equals(bilingualOk),
                Boolean.TRUE.equals(warranty),
                searchKeywords == null || searchKeywords.isBlank() ? null : searchKeywords.strip(),
                Objects.requireNonNullElse(type, OfferType.PRODUCT),
                bundleItems == null
                        ? List.of()
                        : bundleItems.stream()
                                .map(b -> new ProductDetails.BundleItem(
                                        b.offerId(),
                                        b.variantId() == null || b.variantId().isBlank() ? null : b.variantId(),
                                        b.qty()))
                                .toList());
    }
}
