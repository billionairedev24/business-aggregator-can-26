package ca.northline.orders.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A customer's own shop and food orders, newest first, for the account area's "Orders &amp; bookings" (S-58). Ids and
 * amounts only — the caller adds business names through the merchants API.
 */
public interface CustomerOrders {

    /**
     * @param type {@code goods | food}
     * @param state {@code placed | accepted | packing | ready | picked_up | delivered | confirmed | refunded | cancelled}
     * @param delivery {@code pooled | direct} for goods, {@code delivery | pickup} for food
     * @param windowStart the pooled run's window (goods), else null
     * @param etaAt the courier's or the kitchen's time (direct, food), else null
     * @param merchantIds the shops (goods) or the kitchen (food), in the order they appear on the order
     * @param lineIds the order's lines (the escrow references of goods orders)
     * @param items how many things were ordered (sum of quantities)
     */
    record OrderSummary(
            String id,
            @Nullable String ref,
            String type,
            String state,
            @Nullable String delivery,
            long totalCents,
            Instant placedAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant windowStart,
            @Nullable Instant windowEnd,
            @Nullable Instant etaAt,
            List<String> merchantIds,
            List<String> lineIds,
            int items) {

        public OrderSummary {
            merchantIds = List.copyOf(merchantIds);
            lineIds = List.copyOf(lineIds);
        }

        /** Still on its way to the customer. */
        public boolean active() {
            return switch (state) {
                case "placed", "accepted", "packing", "ready", "picked_up" -> true;
                default -> false;
            };
        }
    }

    /** The customer's orders, newest first (at most {@code limit}). */
    List<OrderSummary> recent(String customerId, int limit);

    /** One line of an order as it was bought ({@code unitCents} × {@code qty}, before tax). */
    record Line(String id, String merchantId, String title, int qty, long unitCents) {
        public long amountCents() {
            return unitCents * qty;
        }
    }

    record OrderDetail(OrderSummary order, List<Line> lines) {
        public OrderDetail {
            lines = List.copyOf(lines);
        }
    }

    /** The customer's own order with its lines; empty for anyone else's (S-60 "Something's wrong"). */
    Optional<OrderDetail> detail(String customerId, String orderId);
}
