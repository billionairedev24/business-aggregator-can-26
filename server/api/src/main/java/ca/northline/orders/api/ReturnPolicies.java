package ca.northline.orders.api;

import java.util.Collection;
import java.util.Map;

/**
 * S-151: how long goods can be returned after delivery, by their category (the leaf's own window, else its group's —
 * {@code catalogue.category_return_windows}). Declared here and implemented by the catalogue module, which already
 * depends on orders (S-37 rule: orders never reads {@code catalogue.*}).
 */
public interface ReturnPolicies {

    /** Return windows in days (0 = not returnable) for the offers whose category sets one; others are absent. */
    Map<String, Integer> days(Collection<String> offerIds);
}
