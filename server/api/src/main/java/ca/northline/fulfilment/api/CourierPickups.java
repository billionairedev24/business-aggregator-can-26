package ca.northline.fulfilment.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The courier leg that picks an order up (its first pickup stop): who, when they arrive, whether they're there. */
public interface CourierPickups {

    /** Pickups by order id; an order without a pickup stop has no entry. */
    Map<String, Pickup> of(Collection<String> orderIds);

    /**
     * @param courierAssigned the stop's run has a courier
     * @param courierUserId the courier's user id, once assigned
     * @param eta when the courier reaches the pickup
     * @param arrivedAt when the courier arrived (waiting)
     */
    record Pickup(
            boolean courierAssigned,
            @Nullable String courierUserId,
            @Nullable Instant eta,
            @Nullable Instant arrivedAt) {}
}
