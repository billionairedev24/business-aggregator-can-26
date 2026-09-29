package ca.northline.booking;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/** Published when a customer accepts an itemized quote. Externalized to Kafka topic "booking.quote" keyed by quote id. */
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
