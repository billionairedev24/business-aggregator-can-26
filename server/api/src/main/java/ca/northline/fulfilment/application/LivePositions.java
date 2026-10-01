package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.CourierLocations.Position;
import ca.northline.fulfilment.api.CourierLocations.Subscription;
import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port (S-88): couriers' latest positions and the "moved" signal between api replicas. Valkey in the cloud
 * ({@code northline.live.bus=redis}: keys {@code nl:courier-pos:<courierId>} with a TTL, rate-limit keys
 * {@code nl:courier-ping:<courierId>}, channel {@code nl:courier:<orderId>}); in memory under local/test. Never
 * Postgres: no position history exists anywhere.
 */
public interface LivePositions {

    /** One accepted ping per {@code interval} per courier, across replicas; false = too soon. */
    boolean allow(String courierId, Duration interval);

    /** Keeps the courier's latest position for {@code ttl} (replacing the previous one). */
    void put(String courierId, Position position, Duration ttl);

    Optional<Position> latest(String courierId);

    /** The courier carrying the order moved. */
    void moved(String orderId);

    Subscription subscribe(String orderId, Runnable onMove);
}
