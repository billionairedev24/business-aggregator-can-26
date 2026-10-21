package ca.northline.catalogue.application;

import ca.northline.orders.api.SellableOffers.Item;
import ca.northline.orders.api.SellableOffers.Take;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Outbound port: live offers as the cart sees them, and their stock (S-51). */
public interface StockStore {

    /** Live offer / variant rows for the items; {@code imageId} unchecked (the caller filters approved ones). */
    List<Row> rows(Collection<Item> items, String lang);

    /** Decrements when there is enough; false otherwise. */
    boolean take(Take take);

    void giveBack(Take take);

    /** S-158: the business an offer belongs to. */
    java.util.Optional<String> merchantOf(String offerId);

    record Row(
            String offerId,
            @Nullable String variantId,
            String productId,
            String merchantId,
            String name,
            @Nullable String option,
            @Nullable String unit,
            boolean hasVariants,
            long unitCents,
            int stock,
            @Nullable Integer handlingDays,
            @Nullable String imageId,
            @Nullable String ageClass,
            @Nullable Integer memberDiscountPct) {}
}
