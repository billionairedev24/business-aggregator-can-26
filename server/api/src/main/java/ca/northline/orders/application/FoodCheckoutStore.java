package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code orders.food_checkouts}, and the order rows a placed food order becomes. */
public interface FoodCheckoutStore {

    /** {@code FD-10000}, {@code FD-10001} … */
    String nextRef();

    void insert(CheckoutRow row);

    Optional<CheckoutRow> find(String id);

    void paymentStarted(String id, String paymentIntent);

    /** Pending → placed and the {@code orders.orders} / {@code order_lines} rows; false when it wasn't pending. */
    boolean place(CheckoutRow row, List<OrderLineRow> lines, Instant at);

    /** {@code orders.orders.state} and {@code delivered_at} of a placed order. */
    Optional<OrderState> state(String orderId);

    /**
     * @param lines the priced lines as JSON (what the checkout showed)
     * @param delivery the delivery snapshot as JSON, null for pickup
     */
    record CheckoutRow(
            String id,
            String ref,
            String customerId,
            String merchantId,
            String kitchenName,
            @Nullable String kitchenSlug,
            String state,
            String fulfilmentMode,
            @Nullable Instant scheduledFor,
            @Nullable Instant customerEta,
            @Nullable Integer etaFromMin,
            @Nullable Integer etaToMin,
            String lines,
            long subtotalCents,
            long deliveryFeeCents,
            long serviceFeeCents,
            long feeTaxCents,
            long taxCents,
            long tipCents,
            long totalCents,
            String province,
            @Nullable String taxCalculationId,
            @Nullable String paymentIntent,
            @Nullable String delivery,
            Instant createdAt,
            @Nullable Instant placedAt) {}

    /** @param modifiers JSON list the kitchen display reads ({@code [{"name": "Large"}, …]}) */
    record OrderLineRow(
            String id,
            @Nullable String menuItemId,
            @Nullable String comboId,
            String title,
            int qty,
            long unitCents,
            String modifiers) {}

    record OrderState(String state, @Nullable Instant deliveredAt) {}
}
