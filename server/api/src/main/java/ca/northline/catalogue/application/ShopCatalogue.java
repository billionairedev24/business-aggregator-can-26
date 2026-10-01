package ca.northline.catalogue.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what the consumer Shop reads from {@code catalogue.*} — approved, live offers of the given merchants
 * in shop categories other than {@code excluded} (banned leaves). Merchants and markets come from
 * {@code merchants.api.ShopDirectory}; the adapter never reads another module's schema.
 */
public interface ShopCatalogue {

    /** The shop taxonomy (groups and leaves) with names in {@code lang} (French from {@code category_labels}). */
    List<Category> categories(String lang);

    /** One row per merchant with live offers, restricted to {@code categoryId} when given. */
    List<ShopStats> shops(Collection<String> merchantIds, @Nullable String categoryId, Collection<String> excluded);

    /**
     * Products by popularity (30-day sales, then newest), each with its cheapest offer among the merchants.
     *
     * @param categoryId a leaf, or null for every shop category
     */
    List<ProductRow> popular(
            Collection<String> merchantIds,
            @Nullable String categoryId,
            Collection<String> excluded,
            String lang,
            int limit);

    /** Distinct products live in {@code categoryId}. */
    int productCount(Collection<String> merchantIds, String categoryId);

    /**
     * A shop product's record, if some shop sells it (approved and live) anywhere. {@code productId} may also be one of
     * its offers' ids: search results (S-44) are offers, and the record carries the product's own id (S-48).
     */
    Optional<ProductRecord> product(String productId, String lang);

    /** Live offers of the product by the given merchants. */
    List<OfferRow> offers(String productId, Collection<String> merchantIds);

    /** Variants of the offers, by position. */
    List<VariantRow> variants(Collection<String> offerIds);

    /** Up to {@code perShop} other live products of each merchant, most popular first. */
    List<MoreRow> moreFrom(Collection<String> merchantIds, String exceptProductId, String lang, int perShop);

    /**
     * @param imageIds the record's images (shared catalogue images), main first
     */
    record ProductRecord(
            String productId,
            String name,
            @Nullable String brand,
            @Nullable String description,
            List<String> bullets,
            @Nullable String unit,
            String categoryId,
            List<String> imageIds) {}

    /**
     * @param stock the offer's stock, or the sum of its variants'
     * @param handlingDays as in {@link ShopStats}
     * @param imageIds own images when the offer uses its own, else empty (the record's apply)
     */
    record OfferRow(
            String offerId,
            String merchantId,
            long priceCents,
            @Nullable Long compareAtCents,
            @Nullable String condition,
            int stock,
            @Nullable Integer lowStockAt,
            @Nullable String returnsPolicy,
            String variantTheme,
            @Nullable Integer handlingDays,
            List<String> imageIds) {}

    record VariantRow(String offerId, String variantId, String value, long priceCents, int stock) {}

    record MoreRow(String merchantId, String productId, String name, long priceCents) {}

    record Category(String id, @Nullable String parentId, String name) {

        /** The last segment of the id: {@code shop.food-and-grocery.bakery} → {@code bakery}. */
        public String slug() {
            return id.substring(id.lastIndexOf('.') + 1);
        }

        public boolean leaf() {
            return parentId != null;
        }
    }

    /**
     * @param departmentId the leaf most of the merchant's live products are in
     * @param handlingDays the shortest handling time (0 same day, 1 next day, 2) of an in-stock offer that goes on
     *     pooled runs; null when there is none
     */
    record ShopStats(
            String merchantId,
            String departmentId,
            int products,
            @Nullable Integer handlingDays) {}

    /**
     * @param imageId the offer's main image candidate (own or the catalogue record's) — the caller checks it's approved
     * @param handlingDays as in {@link ShopStats}, for this offer
     */
    record ProductRow(
            String productId,
            String offerId,
            String merchantId,
            String name,
            @Nullable String unit,
            long priceCents,
            @Nullable String imageId,
            @Nullable Integer handlingDays,
            int sellers) {}
}
