package ca.northline.orders.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The shop funnel's counts for the console's analytics (S-95): carts, checkouts started, checkouts paid. */
public interface ShopFunnel {

    Counts counts(MerchantScope scope, Instant from, Instant to);

    /**
     * @param carts carts that had an item added in the period; null when scoped to some businesses (a cart's items
     *     are offers, and which business sells an offer is the catalogue's)
     */
    record Counts(@Nullable Long carts, long checkoutsStarted, long checkoutsPaid) {}
}
