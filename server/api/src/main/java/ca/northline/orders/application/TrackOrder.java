package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The customer's order after checkout (S-52, design 06 {@code confirmed}): what was bought from which shop, the
 * delivery (pooled run or direct courier) and the four steps of the design's timeline, from the order's state and the
 * shops' packing. Only the customer who placed it sees it (404 otherwise).
 */
public interface TrackOrder {

    OrderTracking view(String customerId, String orderId);

    /**
     * @param state {@code "placed"} | {@code "accepted"} | {@code "packing"} | {@code "ready"} | {@code "picked_up"} |
     *     {@code "delivered"} | {@code "confirmed"} | {@code "refunded"} | {@code "cancelled"}
     * @param steps paid → packing → pickup → delivered, each {@code done} | {@code current} | {@code todo}
     * @param deliveryProof the courier's proof at drop-off: {@code photo} | {@code signature} | {@code pin} (S-78)
     * @param canConfirm the customer can confirm receipt now ({@link ConfirmDelivery}; S-78)
     * @param paysShopsAt when the shops are paid without a confirmation: 7 days after delivery (goods, S-78)
     * @param courier the courier bringing it, live (S-88); null before fulfilment has the order
     */
    record OrderTracking(
            String orderId,
            @Nullable String ref,
            String type,
            String state,
            Instant placedAt,
            long subtotalCents,
            long deliveryFeeCents,
            long taxCents,
            long totalCents,
            Delivery delivery,
            List<ShopProgress> shops,
            List<Step> steps,
            @Nullable Instant deliveredAt,
            @Nullable String deliveryProof,
            @Nullable Instant confirmedAt,
            boolean canConfirm,
            @Nullable Instant paysShopsAt,
            @Nullable CourierProgress courier) {

        public OrderTracking {
            shops = List.copyOf(shops);
            steps = List.copyOf(steps);
        }
    }

    /**
     * @param kind {@code pooled} | {@code direct} | {@code pickup}
     * @param day the run's day in the market's time zone: {@code today} | {@code tomorrow} | {@code later}
     * @param etaAt the direct courier's expected arrival
     */
    record Delivery(
            String kind,
            @Nullable String runLabel,
            @Nullable String day,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            int households,
            @Nullable Instant etaAt) {}

    record ShopProgress(String merchantId, String name, int items, boolean packed) {}

    /** @param key {@code paid} | {@code packing} | {@code pickup} | {@code delivered} */
    record Step(String key, String state) {}
}
