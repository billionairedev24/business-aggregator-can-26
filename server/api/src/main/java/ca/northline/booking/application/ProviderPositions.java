package ca.northline.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port (mobile gaps part 2): the latest position a provider shares while on the way to a visit, like the
 * couriers' (S-88): Valkey keys {@code nl:visit-pos:<bookingId>} with a TTL, replaced on each share, rate-limited by
 * {@code nl:visit-ping:<bookingId>}; memory under local/test. Never Postgres: no trail exists anywhere.
 */
public interface ProviderPositions {

    record Position(double lat, double lng, Instant at) {}

    /** One accepted share per {@code interval} per booking, across replicas; false = too soon. */
    boolean allow(String bookingId, Duration interval);

    void put(String bookingId, Position position, Duration ttl);

    Optional<Position> latest(String bookingId);

    /** Sharing stopped (on site, completed, or the provider turned it off). */
    void clear(String bookingId);
}
