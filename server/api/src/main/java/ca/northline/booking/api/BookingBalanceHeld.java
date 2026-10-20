package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code booking.balance_held} (S-153) — the balance of an accepted deposit quote is now held in escrow too, before the
 * event. The customer is told. Kafka topic {@code booking.booking}, key = booking id.
 */
@Externalized("booking.booking::#{aggregateId()}")
public record BookingBalanceHeld(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        long amountCents,
        long taxCents,
        Instant startsAt)
        implements DomainEvent {}
