package ca.northline.orders.application;

import ca.northline.orders.domain.OrderEnums.LineState;
import ca.northline.orders.domain.OrderEnums.SellerStatus;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the Orders screen — always one merchant's share of each order. */
public final class OrderViews {
    private OrderViews() {}

    public record Line(String id, String title, int qty, long unitCents, LineState state) {}

    /**
     * A row of the packing list. {@code totalCents} = the merchant's lines (qty × unit). Run = the delivery window
     * ({@code windowStartsAt}, cut-off, run label).
     */
    public record OrderSummary(
            String id,
            @Nullable String ref,
            @Nullable String customerId,
            @Nullable String customerName,
            @Nullable String area,
            List<Line> lines,
            long totalCents,
            @Nullable Instant windowStartsAt,
            @Nullable Instant cutoffAt,
            @Nullable String runLabel,
            @Nullable Instant placedAt,
            @Nullable Instant deliveredAt,
            SellerStatus status,
            @Nullable String issueNote) {}

    /** The chips above the table and the headline's cut-off. */
    public record Counts(
            int toPack,
            int awaitingPickup,
            int deliveredToday,
            int issues,
            @Nullable Instant nextCutoff,
            @Nullable String nextRunLabel) {}

    public record OrderBoard(List<OrderSummary> orders, Counts counts) {}
}
