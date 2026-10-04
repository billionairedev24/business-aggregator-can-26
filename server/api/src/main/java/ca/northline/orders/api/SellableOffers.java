package ca.northline.orders.api;

import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the cart and checkout (S-51) need from the catalogue: whether an offer can be bought, its price and stock, and
 * taking stock at checkout. Declared here and implemented by the catalogue module, which already depends on orders
 * (S-37 rule: orders never reads {@code catalogue.*}). Only approved, live offers are sellable.
 */
public interface SellableOffers {

    /** The sellable ones among {@code items} (unknown, unapproved or hidden offers and unknown variants are absent). */
    List<Sellable> find(Collection<Item> items, String lang);

    /**
     * Takes stock for every line, all or nothing: returns the lines there wasn't enough stock for, in which case the
     * caller must roll its transaction back (the other lines were already taken in it). Safe against concurrent
     * checkouts (conditional decrement).
     */
    List<Take> take(List<Take> takes);

    /** Puts stock back (checkout abandoned). */
    void giveBack(List<Take> takes);

    record Item(String offerId, @Nullable String variantId) {}

    record Take(String offerId, @Nullable String variantId, int qty) {}

    /**
     * @param variantId null for an offer without variants
     * @param option the variant's value ("Sliced"), or null
     * @param hasVariants the offer has variants (then a variant must be chosen)
     * @param stock units available (the variant's, else the offer's)
     * @param handlingDays 0 same day, 1 next day, 2; null when it doesn't go on pooled runs
     * @param imageUrl an approved image, or null
     * @param ageClass the age-restriction class its category carries ({@code alcohol | tobacco | cannabis}), or null
     */
    record Sellable(
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
            @Nullable String imageUrl,
            @Nullable String ageClass) {}
}
