package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code payments.courier_tips}. */
public interface TipStore {

    /**
     * @param source {@code checkout} | {@code after_delivery}
     * @param state pending, captured, allocated (owed to the courier), refunded or canceled
     */
    record StoredTip(
            String id,
            String orderId,
            String customerId,
            @Nullable String courierUserId,
            long amountCents,
            String source,
            @Nullable String stripePaymentIntent,
            String state,
            Instant createdAt,
            @Nullable Instant capturedAt) {}

    /** False when the order already has a tip from this source (checkout) / a live one (after delivery). */
    boolean insert(StoredTip tip);

    Optional<StoredTip> find(String id);

    /** Locked for the rest of the transaction. */
    Optional<StoredTip> lock(String id);

    List<StoredTip> ofOrder(String orderId);

    /** The order's checkout tip, locked. */
    Optional<StoredTip> checkoutTip(String orderId);

    void captured(String id, Instant at);

    void courier(String id, String courierUserId);

    void allocated(String id, Instant at);

    void canceled(String id);

    void refunded(String id, String reason, String staffId, @Nullable String stripeRefund, Instant at);
}
