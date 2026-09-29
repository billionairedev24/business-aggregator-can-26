package ca.northline.orders.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Order figures for the Studio dashboard (seller side). */
public interface OrderInsights {

    /** Orders with lines still to pack, and the earliest upcoming cut-off among them. */
    Packing packing(String merchantId, Instant now);

    /** The packing list of the next run(s): orders whose window starts in [from, to), to pack or packed. */
    List<RunOrder> run(String merchantId, Instant from, Instant to);

    /** Orders and items sold in [from, to) (by placed date, cancelled excluded). */
    Volume volume(String merchantId, Instant from, Instant to);

    record Packing(
            int toPack,
            @Nullable Instant earliestCutoff,
            @Nullable String runLabel) {}

    /**
     * @param items the merchant's lines, e.g. "Wiper blades ×2"
     * @param packed all of the merchant's lines are packed
     */
    record RunOrder(
            String orderId,
            @Nullable String ref,
            @Nullable String runLabel,
            @Nullable String customerId,
            @Nullable String area,
            List<Item> items,
            boolean packed) {}

    record Item(String title, int qty) {}

    record Volume(long orders, long items) {}
}
