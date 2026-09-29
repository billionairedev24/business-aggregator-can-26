package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * A job moved along the job flow. Externalized to Kafka topic {@code booking.booking}, key = booking id, so consumers
 * see a booking's transitions in order. Payloads carry ids only (no address, no GPS).
 */
public sealed interface BookingProgressed extends DomainEvent {

    String merchantId();

    String actorId();

    /** {@code booking.en_route} — the member started travelling; the customer gets a live ETA. */
    @Externalized("booking.booking::#{aggregateId()}")
    record BookingEnRoute(String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId)
            implements BookingProgressed {}

    /** {@code booking.on_site} — geofenced, timestamped check-in (proof of arrival). */
    @Externalized("booking.booking::#{aggregateId()}")
    record BookingOnSite(String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId)
            implements BookingProgressed {}

    /**
     * {@code booking.completed} — work done; escrow releases on sign-off or 48 h later. {@code photoCount} = 0 delays the
     * release by 48 h (quality: "completion photos").
     */
    @Externalized("booking.booking::#{aggregateId()}")
    record BookingCompleted(
            String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId, int photoCount)
            implements BookingProgressed {}

    /** {@code booking.scope_changed} — extra parts / scope change sent to the customer for in-app approval. */
    @Externalized("booking.booking::#{aggregateId()}")
    record BookingScopeChangeRequested(
            String eventId,
            Instant occurredAt,
            String aggregateId,
            String merchantId,
            String actorId,
            String approvalId,
            long amountCents)
            implements BookingProgressed {}
}
