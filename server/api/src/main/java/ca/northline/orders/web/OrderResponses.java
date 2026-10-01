package ca.northline.orders.web;

import ca.northline.orders.domain.OrderEnums.LineState;
import ca.northline.orders.domain.OrderEnums.SellerStatus;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class OrderResponses {
    private OrderResponses() {}

    record LineResponse(String id, String title, int qty, long unitCents, LineState state) {}

    record OrderResponse(
            String id,
            @Nullable String ref,
            @Nullable String customerName,
            @Nullable String area,
            List<LineResponse> lines,
            long totalCents,
            @Nullable Instant windowStartsAt,
            @Nullable Instant cutoffAt,
            @Nullable String runLabel,
            @Nullable Instant placedAt,
            @Nullable Instant deliveredAt,
            SellerStatus status,
            @Nullable String issueNote,
            @Nullable CourierPickupResponse courierPickup) {}

    record CourierPickupResponse(
            boolean courierAssigned,
            @Nullable String runLabel,
            @Nullable Instant eta,
            @Nullable Instant arrivedAt,
            @Nullable Instant pickedUpAt) {}

    record CountsResponse(
            int toPack,
            int awaitingPickup,
            int deliveredToday,
            int issues,
            @Nullable Instant nextCutoff,
            @Nullable String nextRunLabel) {}

    /** {@code {"items": [...], "counts": {...}}}. */
    record BoardResponse(List<OrderResponse> items, CountsResponse counts) {}
}
