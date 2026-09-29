package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code escrow.released} — money held for a job / order line moved to the merchant's balance (net of the take rate).
 * Kafka topic {@code payments.escrow}, key = escrow id. Schema {@code events/payments.escrow_released.v1.schema.json}.
 */
@Externalized("payments.escrow::#{aggregateId()}")
public record EscrowReleased(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String refType,
        String refId,
        long grossCents,
        long feeCents,
        long netCents)
        implements DomainEvent {}
