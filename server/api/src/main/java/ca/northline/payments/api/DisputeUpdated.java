package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code dispute.updated} — a dispute changed hands before it was decided: {@code opened} (the customer opened it; the
 * merchant has until {@code respondBy}), {@code offer_declined} or {@code offer_expired} (the case went to an agent).
 * The decision itself is {@link DisputeDecided}. The merchant's team is emailed (S-13). Kafka topic
 * {@code payments.dispute}, key = dispute id. Schema {@code events/payments.dispute_updated.v1.schema.json}.
 */
@Externalized("payments.dispute::#{aggregateId()}")
public record DisputeUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String caseNumber,
        String change,
        long amountCents,
        @Nullable Instant respondBy)
        implements DomainEvent {}
