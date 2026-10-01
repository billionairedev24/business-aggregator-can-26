package ca.northline.orders.application;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: what fulfilment needs to know about an order to send a courier (S-86). */
public interface DispatchFacts {

    Optional<Facts> of(String orderId);

    /** Moves a goods or food order to {@code picked_up} if it is still before it; false otherwise. */
    boolean pickedUp(String orderId);

    /**
     * @param addressId the shop order's delivery address ({@code identity.addresses})
     * @param deliveryArea the city the order is delivered in (shop orders)
     * @param foodDelivery a food order's delivery snapshot (JSON of {@code FoodCheckout.Delivery}), null for pickup
     */
    record Facts(
            String orderId,
            @Nullable String customerId,
            @Nullable String addressId,
            @Nullable String deliveryArea,
            @Nullable String windowId,
            @Nullable String foodDelivery) {}
}
