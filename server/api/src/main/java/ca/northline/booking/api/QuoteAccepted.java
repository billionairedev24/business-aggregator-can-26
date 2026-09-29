package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code quote.accepted} — a customer accepted an itemized quote (→ booking + escrow hold for {@code totalCents}).
 * Externalized to Kafka topic {@code booking.quote}, key = quote id. Schema: {@code booking.quote_accepted.v2}.
 */
@Externalized("booking.quote::#{aggregateId()}")
public record QuoteAccepted(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        long totalCents,
        long depositCents)
        implements DomainEvent {
    @Override
    public int version() {
        return 2;
    }
}
