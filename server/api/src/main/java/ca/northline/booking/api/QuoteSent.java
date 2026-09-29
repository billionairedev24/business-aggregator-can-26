package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code quote.sent} — a merchant sent an itemized quote (version 1) or a revision (version &gt; 1, the prior version
 * is {@code supersededQuoteId}). The customer gets a push with the full breakdown. Externalized to Kafka topic
 * {@code booking.quote}, key = quote id. Schema: {@code booking.quote_sent.v1}.
 */
@Externalized("booking.quote::#{aggregateId()}")
public record QuoteSent(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String requestId,
        String merchantId,
        String actorId,
        int quoteVersion,
        long totalCents,
        long depositCents,
        Instant validUntil,
        @Nullable String supersededQuoteId)
        implements DomainEvent {}
