package ca.northline.catalogue.domain;

import static ca.northline.catalogue.domain.ListingMessages.*;

import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Everything the product editor edits, tab by tab: identity & category, variants, images, price / stock /
 * fulfilment, compliance. For an offer on a shared catalogue record the content fields (brand … bullets) mirror the
 * record.
 */
public record ProductDetails(
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
        List<Variant> variants,
        // images
        ImageSource imageSource,
        List<String> ownImageIds,
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
        @Nullable String searchKeywords) {

    private static final Pattern PROMO = Pattern.compile(
            "(?<![\\w-])(sale|free|best|cheapest|discount|deal|promo|clearance|\\d+\\s?% off)(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    public ProductDetails {
        title = title.strip();
        gtin = gtin == null || gtin.isBlank() ? null : Gtin.normalize(gtin);
        sku = sku == null || sku.isBlank() ? null : sku.strip();
        attributes = Map.copyOf(attributes);
        bullets = bullets.stream().map(String::strip).filter(b -> !b.isEmpty()).toList();
        variants = variantTheme == VariantTheme.NONE ? List.of() : List.copyOf(variants);
        ownImageIds = List.copyOf(ownImageIds);
        fulfilment = fulfilment.stream().distinct().toList();
    }

    /** The same details with the content (identity fields) of a shared catalogue record the seller can't edit. */
    public ProductDetails withContentOf(CatalogRecord r) {
        return new ProductDetails(
                r.identifierType(),
                r.gtin(),
                title,
                r.brand(),
                r.mpn(),
                r.categoryId(),
                r.attributes(),
                r.description(),
                r.bullets(),
                variantTheme,
                variants,
                imageSource,
                ownImageIds,
                sku,
                priceCents,
                compareAtCents,
                costCents,
                condition,
                stock,
                lowStockAt,
                fulfilment,
                handlingTime,
                returnsPolicy,
                countryOfOrigin,
                restrictedOk,
                bilingualOk,
                warranty,
                searchKeywords);
    }

    /** Same details with a generated SKU (listings created without one). */
    public ProductDetails withSku(String generated) {
        return new ProductDetails(
                identifierType,
                gtin,
                title,
                brand,
                mpn,
                categoryId,
                attributes,
                description,
                bullets,
                variantTheme,
                variants,
                imageSource,
                ownImageIds,
                generated,
                priceCents,
                compareAtCents,
                costCents,
                condition,
                stock,
                lowStockAt,
                fulfilment,
                handlingTime,
                returnsPolicy,
                countryOfOrigin,
                restrictedOk,
                bilingualOk,
                warranty,
                searchKeywords);
    }

    /** Same details with a new price and stock (bulk quick update, integration sync). */
    public ProductDetails withPriceAndStock(long newPriceCents, int newStock) {
        return new ProductDetails(
                identifierType,
                gtin,
                title,
                brand,
                mpn,
                categoryId,
                attributes,
                description,
                bullets,
                variantTheme,
                variants,
                imageSource,
                ownImageIds,
                sku,
                newPriceCents,
                compareAtCents,
                costCents,
                condition,
                newStock,
                lowStockAt,
                fulfilment,
                handlingTime,
                returnsPolicy,
                countryOfOrigin,
                restrictedOk,
                bilingualOk,
                warranty,
                searchKeywords);
    }

    /**
     * Rules checked on every save (draft included) that need more than the request shape: promo words, GTIN check
     * digits, the category, cross-field money rules, variants.
     */
    public List<Violation> validate(@Nullable CategoryProfile category) {
        var out = new ArrayList<Violation>();
        if (title.isEmpty()) {
            out.add(new Violation("title", "required", TITLE_REQUIRED));
        } else if (PROMO.matcher(title).find()) {
            out.add(new Violation("title", "promo_words", TITLE_PROMO));
        }
        if (identifierType != IdentifierType.NONE) {
            if (gtin == null) {
                out.add(new Violation("gtin", "required", GTIN_REQUIRED));
            } else {
                Gtin.problem("gtin", gtin).ifPresent(out::add);
            }
        }
        if (categoryId != null) {
            if (category == null) {
                out.add(new Violation("categoryId", "required", CATEGORY_REQUIRED));
            } else if (!category.isShop()) {
                out.add(new Violation("categoryId", "category", CATEGORY_WRONG_ROOT));
            } else if (!category.leaf()) {
                out.add(new Violation("categoryId", "leaf", CATEGORY_LEAF));
            }
        }
        if (compareAtCents != null && compareAtCents <= priceCents) {
            out.add(new Violation("compareAtCents", "range", COMPARE_AT_HIGHER));
        }
        if (returnsPolicy == ReturnsPolicy.FINAL_SALE && (category == null || !category.perishable())) {
            out.add(new Violation("returnsPolicy", "perishable", FINAL_SALE_PERISHABLE));
        }
        validateVariants(out);
        return out;
    }

    private void validateVariants(List<Violation> out) {
        var values = new HashSet<String>();
        var skus = new HashSet<String>();
        for (int i = 0; i < variants.size(); i++) {
            var v = variants.get(i);
            var at = "variants[" + i + "].";
            if (v.value().isEmpty()) {
                out.add(new Violation(at + "value", "required", VARIANT_VALUE_REQUIRED));
            } else if (!values.add(v.value().toLowerCase(Locale.ROOT))) {
                out.add(new Violation(at + "value", "duplicate", VARIANT_DUPLICATE));
            }
            if (v.sku().isEmpty()) {
                out.add(new Violation(at + "sku", "required", SKU_REQUIRED));
            } else if (!skus.add(v.sku().toLowerCase(Locale.ROOT))) {
                out.add(new Violation(at + "sku", "duplicate", SKU_DUPLICATE));
            }
            var gtinOfVariant = v.gtin();
            if (gtinOfVariant != null) {
                Gtin.problem(at + "gtin", gtinOfVariant).ifPresent(out::add);
            }
        }
    }

    /** Completeness meter: identity · variants · images · offer · compliance · preview (always complete). */
    public Completeness completeness(@Nullable CategoryProfile category, List<String> catalogueImageIds) {
        return new Completeness.Tally()
                .section(identityGaps(category))
                .section(
                        variantTheme != VariantTheme.NONE && variants.isEmpty()
                                ? List.of(new Violation("variants", "required", VARIANTS_REQUIRED))
                                : List.of())
                .section(
                        hasImages(catalogueImageIds)
                                ? List.of()
                                : List.of(new Violation("images", "required", IMAGES_REQUIRED)))
                .section(offerGaps())
                .section(complianceGaps())
                .always()
                .result();
    }

    public boolean hasImages(List<String> catalogueImageIds) {
        return imageSource == ImageSource.SHARED ? !catalogueImageIds.isEmpty() : !ownImageIds.isEmpty();
    }

    private List<Violation> identityGaps(@Nullable CategoryProfile category) {
        var gaps = new ArrayList<Violation>();
        if (title.isEmpty()) {
            gaps.add(new Violation("title", "required", TITLE_REQUIRED));
        }
        if (categoryId == null || category == null || !category.leaf()) {
            gaps.add(new Violation("categoryId", "required", CATEGORY_REQUIRED));
        } else {
            for (var spec : category.attributes()) {
                var value = attributes.get(spec.key());
                if (spec.required() && (value == null || value.isBlank())) {
                    gaps.add(new Violation(
                            "attributes." + spec.key(), "required", ATTRIBUTE_REQUIRED.formatted(spec.label())));
                }
            }
        }
        return gaps;
    }

    private List<Violation> offerGaps() {
        var gaps = new ArrayList<Violation>();
        if (priceCents <= 0) {
            gaps.add(new Violation("priceCents", "range", PRICE_POSITIVE));
        }
        if (fulfilment.isEmpty()) {
            gaps.add(new Violation("fulfilment", "required", FULFILMENT_REQUIRED));
        }
        return gaps;
    }

    private List<Violation> complianceGaps() {
        var gaps = new ArrayList<Violation>();
        if (countryOfOrigin == null || countryOfOrigin.isBlank()) {
            gaps.add(new Violation("countryOfOrigin", "required", ORIGIN_REQUIRED));
        }
        if (!restrictedOk) {
            gaps.add(new Violation("restrictedOk", "required", RESTRICTED_REQUIRED));
        }
        if (!bilingualOk) {
            gaps.add(new Violation("bilingualOk", "required", BILINGUAL_REQUIRED));
        }
        return gaps;
    }

    /** One variant row: value (e.g. "22 in"), own SKU, optional GTIN, price and stock. */
    public record Variant(
            @Nullable String id,
            String value,
            String sku,
            @Nullable String gtin,
            long priceCents,
            int stock) {
        public Variant {
            value = value.strip();
            sku = sku.strip();
            gtin = gtin == null || gtin.isBlank() ? null : Gtin.normalize(gtin);
        }
    }
}
