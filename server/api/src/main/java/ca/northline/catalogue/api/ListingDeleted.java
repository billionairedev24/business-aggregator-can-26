package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code listing.deleted} — An owner deleted a listing (audit trail + search removal). Externalized to Kafka topic {@code catalogue.listing}, key = listing id. Schema: {@code resources/events/catalogue.listing_deleted.v1.schema.json}.
 * {@code aggregateId} is the listing id (offer or service), {@code kind} is {@code service} or {@code product}.
 */
@Externalized("catalogue.listing::#{aggregateId()}")
public record ListingDeleted(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String kind, String actorId)
        implements DomainEvent {}
