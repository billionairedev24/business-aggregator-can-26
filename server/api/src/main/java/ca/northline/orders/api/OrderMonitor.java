package ca.northline.orders.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Goods and food orders for the console's orders monitor (S-81): the recent ones and every one still open, of the
 * businesses in scope. Ids, references, states, times and money only — names are looked up by the caller.
 */
public interface OrderMonitor {

    /**
     * Orders placed since {@code since} or not finished yet (not delivered, confirmed, refunded or cancelled), newest
     * first, at most {@code limit}; with {@code ref}, only references starting with it (case-insensitive).
     */
    List<MonitoredOrder> orders(MerchantScope scope, Instant since, @Nullable String ref, int limit);

    /**
     * @param type {@code goods} | {@code food}
     * @param state the order's state ({@code placed} … {@code cancelled})
     * @param merchantIds the businesses with a line on it, first line first
     * @param totalCents what the customer pays: lines, fees, tax and tip
     * @param windowEndsAt a pooled order's delivery window end
     * @param issue a line has a reported issue or is short or refunded
     */
    record MonitoredOrder(
            String id,
            @Nullable String ref,
            String type,
            String state,
            @Nullable String customerId,
            List<String> merchantIds,
            long totalCents,
            Instant placedAt,
            @Nullable Instant windowEndsAt,
            @Nullable Instant deliveredAt,
            boolean issue) {

        public MonitoredOrder {
            merchantIds = List.copyOf(merchantIds);
        }
    }
}
