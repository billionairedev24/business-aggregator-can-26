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
        @Nullable String searchKeywords,
        // bundle (S-65)
        OfferType type,
        List<BundleItem> bundleItems) {

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
        bundleItems = type == OfferType.BUNDLE ? List.copyOf(bundleItems) : List.of();
        if (type
                == OfferType
                        .BUNDLE) { // a bundle has no identifier and no variants of its own; its stock follows the items
            identifierType = IdentifierType.NONE;
            gtin = null;
            variantTheme = VariantTheme.NONE;
            variants = List.of();
            stock = 0;
            lowStockAt = null;
        }
    }

    /** A product (no bundle) — every caller before S-65. */
    public ProductDetails(
            IdentifierType identifierType,
            @Nullable String gtin,
            String title,
            @Nullable String brand,
            @Nullable String mpn,
            @Nullable String categoryId,
            Map<String, String> attributes,
            @Nullable String description,
            List<String> bullets,
            VariantTheme variantTheme,
            List<Variant> variants,
            ImageSource imageSource,
            List<String> ownImageIds,
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
            @Nullable String countryOfOrigin,
            boolean restrictedOk,
            boolean bilingualOk,
            boolean warranty,
            @Nullable String searchKeywords) {
        this(
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
                searchKeywords,
                OfferType.PRODUCT,
                List.of());
    }

    public boolean isBundle() {
        return type == OfferType.BUNDLE;
    }

    /** Every image id the listing uses: its own images, then each variant's (S-65). */
    public List<String> allOwnImageIds() {
        var all = new ArrayList<>(ownImageIds);
        variants.forEach(
                v -> v.imageIds().stream().filter(id -> !all.contains(id)).forEach(all::add));
        return List.copyOf(all);
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
                searchKeywords,
                type,
                bundleItems);
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
                searchKeywords,
                type,
                bundleItems);
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
                searchKeywords,
                type,
                bundleItems);
    }

    /**
     * Integration sync (S-35): price and stock per SKU from the connected platform, their source of truth. A single
     * offer takes its SKU's values; with variants, each variant takes its SKU's values, a variant the platform no longer
     * has goes to 0 in stock, and the offer shows the lowest variant price and the total stock. Same details when
     * nothing changed.
     */
    public ProductDetails withSyncedStock(Map<String, PriceStock> bySku) {
        if (variants.isEmpty()) {
            var own = sku == null ? null : bySku.get(sku);
            return own == null || (own.priceCents() == priceCents && own.stock() == stock)
                    ? this
                    : withPriceAndStock(own.priceCents(), own.stock());
        }
        var synced = variants.stream()
                .map(v -> {
                    var found = bySku.get(v.sku());
                    return found == null
                            ? v.withPriceAndStock(v.priceCents(), 0)
                            : v.withPriceAndStock(found.priceCents(), found.stock());
                })
                .toList();
        if (synced.equals(variants)) {
            return this;
        }
        var lowest = synced.stream().mapToLong(Variant::priceCents).min().orElse(priceCents);
        var total = synced.stream().mapToInt(Variant::stock).sum();
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
                synced,
                imageSource,
                ownImageIds,
                sku,
                lowest,
                compareAtCents,
                costCents,
                condition,
                total,
                lowStockAt,
                fulfilment,
                handlingTime,
                returnsPolicy,
                countryOfOrigin,
                restrictedOk,
                bilingualOk,
                warranty,
                searchKeywords,
                type,
                bundleItems);
    }

    /** A price and a stock level from a connected platform (S-35). */
    public record PriceStock(long priceCents, int stock) {}

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
        validateBundle(out);
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
            if (v.imageIds().size() > IMAGES_MAX) {
                out.add(new Violation(at + "imageIds", "length", IMAGES_TOO_MANY));
            }
        }
    }

    private void validateBundle(List<Violation> out) {
        if (bundleItems.size() > BUNDLE_ITEMS_MAX) {
            out.add(new Violation("bundleItems", "length", BUNDLE_ITEMS_TOO_MANY));
        }
        var seen = new HashSet<String>();
        for (int i = 0; i < bundleItems.size(); i++) {
            var item = bundleItems.get(i);
            if (item.qty() < 1 || item.qty() > BUNDLE_QTY_MAX) {
                out.add(new Violation("bundleItems[" + i + "].qty", "range", BUNDLE_QTY_RANGE));
            }
            if (!seen.add(item.offerId() + "/" + item.variantId())) {
                out.add(new Violation("bundleItems[" + i + "].offerId", "duplicate", BUNDLE_ITEM_DUPLICATE));
            }
        }
    }

    /** Units in the bundle (2 × socks + 1 × hat = 3). */
    public int bundleUnits() {
        return bundleItems.stream().mapToInt(BundleItem::qty).sum();
    }

    /** Completeness meter: identity · variants · images · offer · compliance · preview (always complete). */
    public Completeness completeness(@Nullable CategoryProfile category, List<String> catalogueImageIds) {
        return new Completeness.Tally()
                .section(identityGaps(category))
                .section(
                        isBundle()
                                ? (bundleUnits() < 2
                                        ? List.of(new Violation("bundleItems", "required", BUNDLE_ITEMS_REQUIRED))
                                        : List.of())
                                : variantTheme != VariantTheme.NONE && variants.isEmpty()
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
            int stock,
            List<String> imageIds) {
        public Variant {
            value = value.strip();
            sku = sku.strip();
            gtin = gtin == null || gtin.isBlank() ? null : Gtin.normalize(gtin);
            imageIds = List.copyOf(imageIds);
        }

        /** A variant that inherits the listing's images. */
        public Variant(
                @Nullable String id, String value, String sku, @Nullable String gtin, long priceCents, int stock) {
            this(id, value, sku, gtin, priceCents, stock, List.of());
        }

        /** Same variant with a new price and stock (integration sync); its images stay. */
        Variant withPriceAndStock(long newPriceCents, int newStock) {
            return new Variant(id, value, sku, gtin, newPriceCents, newStock, imageIds);
        }
    }

    /**
     * One line of a bundle (S-65): {@code qty} of one of the business's own products, of one variant when it has some.
     */
    public record BundleItem(String offerId, @Nullable String variantId, int qty) {}
}
