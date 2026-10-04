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

    /**
     * Age-restricted items (2026-10-04): nobody of age took the order — {@code returned}; false when it was already
     * returned (a retried event). The refunded lines are marked: every line ({@code allLines}, goods) or the
     * age-restricted ones (food).
     */
    boolean markReturned(String orderId, boolean allLines, Instant at);

    /** What the order's age-restricted lines cost, before tax ({@code unit_cents × qty} of lines with an age class). */
    long restrictedCents(String orderId);

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
