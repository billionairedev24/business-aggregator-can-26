package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * The customer signed a completed job off (consumer app, S-100): payments releases the escrow at once instead of 48 h
 * after completion. In-process only (not externalized: no worker needs it yet). Ids only.
 */
public record BookingSignedOff(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String customerId)
        implements DomainEvent {}
