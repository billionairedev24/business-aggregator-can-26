package ca.northline.orders.application;

import ca.northline.orders.application.OrderViews.Line;
import ca.northline.orders.domain.MerchantOrder;
import ca.northline.orders.domain.OrderEnums.OrderState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: the merchant's orders (read side) and the packing aggregate (write side). */
public interface OrderQueries {

    /** Orders with lines of this merchant that are open, or were delivered / had an issue since {@code since}. */
    List<OrderRow> board(String merchantId, Instant since);

    Optional<OrderRow> row(String merchantId, String orderId);

    Optional<MerchantOrder> packing(String merchantId, String orderId);

    /** Persists the packed lines and the order state. */
    void savePacking(MerchantOrder order, String actorId, Instant at);

    record OrderRow(
            String id,
            @Nullable String ref,
            @Nullable String customerId,
            @Nullable String area,
            OrderState state,
            List<Line> lines,
            @Nullable String issueNote,
            @Nullable Instant windowStartsAt,
            @Nullable Instant cutoffAt,
            @Nullable String runLabel,
            @Nullable Instant placedAt,
            @Nullable Instant deliveredAt) {}
}
