package ca.northline.catalogue.application;

import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what the consumer Shop reads from {@code catalogue.*} — approved, live offers of the given merchants
 * in shop categories other than {@code excluded} (banned leaves). Merchants and markets come from
 * {@code merchants.api.ShopDirectory}; the adapter never reads another module's schema.
 */
public interface ShopCatalogue {

    /** The shop taxonomy (groups and leaves) with names in {@code lang} (French from {@code category_labels}). */
    List<Category> categories(String lang);

    /**
     * One row per merchant with live offers, restricted to {@code categoryId} when given.
     *
     * @param departmentId the leaf most of the merchant's live products are in
     * @param handlingDays the shortest handling time (0 same day, 1 next day, 2) of an in-stock offer that goes on
     *     pooled runs; null when there is none
     */
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

    record Category(String id, @Nullable String parentId, String name) {

        /** The last segment of the id: {@code shop.food-and-grocery.bakery} → {@code bakery}. */
        public String slug() {
            return id.substring(id.lastIndexOf('.') + 1);
        }

        public boolean leaf() {
            return parentId != null;
        }
    }

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
