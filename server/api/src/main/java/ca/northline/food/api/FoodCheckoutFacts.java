package ca.northline.food.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What checkout needs to know about a kitchen for an address (S-57): open now or not, how it fulfils, whether it
 * delivers that far, the courier fee and the times, and the scheduled-order windows it offers.
 */
public interface FoodCheckoutFacts {

    /** @param lat the delivery address, or null (pickup, or not known) */
    Optional<Kitchen> kitchen(String merchantId, @Nullable Double lat, @Nullable Double lng);

    /**
     * @param province the kitchen's province (place of supply for pickup)
     * @param delivers the address is within the delivery radius; null when either point is unknown
     * @param deliveryFeeCents the direct courier's fee for the distance
     * @param etaFromMin minutes until a delivery arrives (prep + ride), {@code etaToMin} 10 later
     * @param pickupFromMin minutes until a pickup is ready, {@code pickupToMin} 5 later
     * @param slots the start of every 30-minute window a scheduled order may pick (within opening hours)
     */
    record Kitchen(
            String merchantId,
            String name,
            @Nullable String slug,
            @Nullable String province,
            boolean open,
            boolean paused,
            List<String> fulfilment,
            @Nullable Boolean delivers,
            @Nullable Double distanceKm,
            long deliveryFeeCents,
            int prepMin,
            int etaFromMin,
            int etaToMin,
            int pickupFromMin,
            int pickupToMin,
            long minOrderCents,
            int serviceFeeBps,
            List<Instant> slots) {
        public Kitchen {
            fulfilment = List.copyOf(fulfilment);
            slots = List.copyOf(slots);
        }
    }
}
