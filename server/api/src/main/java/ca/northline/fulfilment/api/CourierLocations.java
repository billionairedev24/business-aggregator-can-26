package ca.northline.fulfilment.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where the courier bringing an order is (S-88), for the customer's live tracking (S-52 goods, S-57 food). Positions
 * live only in Valkey (the latest one per courier, a few minutes); they are shown only while the order is on its way —
 * picked up and not yet delivered — and never kept as a trail.
 */
public interface CourierLocations {

    /** The courier's progress towards this order's door while it is on its way; empty otherwise. */
    Optional<Live> forOrder(String orderId);

    /** Calls {@code onMove} on every accepted position of the courier carrying the order, until closed. */
    Subscription subscribe(String orderId, Runnable onMove);

    /**
     * @param position the latest position, null when the courier's app hasn't sent one in the last few minutes
     * @param eta the drop-off ETA: from the live position when there is one, else the run's plan
     * @param stopsBefore drop-offs left before this one
     */
    record Live(@Nullable String courierUserId, @Nullable Position position, @Nullable Instant eta, int stopsBefore) {}

    /** @param heading degrees from north, when the phone reports it */
    record Position(double lat, double lng, @Nullable Double heading, Instant at) {}

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
