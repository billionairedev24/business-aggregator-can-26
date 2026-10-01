package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code booking.confirmed} — a customer booked and paid (the escrow hold is recorded), or accepted a quote with its
 * deposit: the time is the member's. Externalized to Kafka topic {@code booking.booking}, key = booking id (S-55). The
 * partner webhook {@code booking.confirmed} (S-33) and the member's calendar write-back (S-32) follow it. Ids and
 * amounts only — no address, access instructions or contact details.
 *
 * @param memberUserId the team member doing the job
 * @param quoteId the accepted quote, when the booking came from one
 * @param priceCents the job's price before tax; {@code depositCents} what is held now when that is less (events)
 */
@Externalized("booking.booking::#{aggregateId()}")
public record BookingConfirmed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        @Nullable String memberUserId,
        @Nullable String serviceId,
        @Nullable String quoteId,
        String bookingType,
        Instant startsAt,
        Instant endsAt,
        long priceCents,
        long depositCents)
        implements DomainEvent {}
