package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code listing.published} — A listing became visible to customers: automated vetting approved it, or the merchant published an approved, hidden listing. The search indexer (re)indexes it. Externalized to Kafka topic {@code catalogue.listing}, key = listing id. Schema: {@code resources/events/catalogue.listing_published.v1.schema.json}.
 * {@code aggregateId} is the listing id (offer or service), {@code kind} is {@code service} or {@code product}.
 */
@Externalized("catalogue.listing::#{aggregateId()}")
public record ListingPublished(String eventId, Instant occurredAt, String aggregateId, String merchantId, String kind)
        implements DomainEvent {}
