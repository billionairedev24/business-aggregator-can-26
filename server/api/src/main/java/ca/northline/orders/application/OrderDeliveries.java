package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: an order's delivery and confirmation (S-78) and the lines whose escrow they drive. */
public interface OrderDeliveries {

    /** The order, locked for the rest of the transaction (delivery and confirmation of one order run one at a time). */
    Optional<Order> lock(String orderId);

    /** {@code delivered} with the courier's proof; {@code delivered_at} keeps the first value. */
    void markDelivered(String orderId, String proof, Instant at);

    /** {@code confirmed}; {@code delivered_at} is set to {@code at} when the courier's drop-off hasn't been recorded. */
    void markConfirmed(String orderId, Instant at);

    /** The order's lines that hold goods escrow ({@code order_line} references), refunded lines left out. */
    List<String> escrowLines(String orderId);

    /** The businesses with lines on the order, by id (an order from several shops has several). */
    List<String> merchants(String orderId);

    record Order(
            String id,
            @Nullable String customerId,
            String type,
            String state,
            @Nullable Instant deliveredAt,
            @Nullable Instant confirmedAt) {}
}
