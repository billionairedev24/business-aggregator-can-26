package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code orders.checkouts} and the order a placed checkout becomes. */
public interface CheckoutStore {

    String nextRef();

    void insert(Checkout checkout);

    Optional<Checkout> find(String customerId, String checkoutId);

    List<Checkout> open(String customerId);

    List<Checkout> expired(Instant now, int limit);

    /** Open → abandoned; false when it wasn't open any more (someone else got there first). */
    boolean abandon(String checkoutId);

    /** Open → placed; false when it wasn't open. */
    boolean placed(String checkoutId, Instant at);

    /** Inserts {@code orders.orders} + {@code order_lines} (state placed / pending). */
    void createOrder(Checkout checkout, @Nullable String deliveryArea, @Nullable Instant scheduledFor, Instant at);

    /**
     * @param state {@code open} | {@code placed} | {@code abandoned}
     * @param kind {@code pooled} | {@code direct}
     */
    record Checkout(
            String id,
            String customerId,
            String state,
            String orderId,
            String ref,
            String market,
            String kind,
            @Nullable String windowId,
            String substitution,
            String addressId,
            String province,
            long subtotalCents,
            long deliveryFeeCents,
            long deliveryTaxCents,
            long taxCents,
            long totalCents,
            @Nullable String deliveryPaymentIntent,
            List<Line> lines,
            Instant createdAt,
            Instant expiresAt) {

        public Checkout {
            lines = List.copyOf(lines);
        }
    }

    record Line(
            String lineId,
            String offerId,
            @Nullable String variantId,
            String productId,
            String merchantId,
            String name,
            @Nullable String option,
            int qty,
            long unitCents,
            long amountCents,
            long taxCents,
            @Nullable String taxCalculationId,
            String paymentIntent) {}
}
