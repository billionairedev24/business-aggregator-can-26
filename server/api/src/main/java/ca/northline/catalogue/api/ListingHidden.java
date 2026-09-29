package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code listing.hidden} — An approved listing was hidden by the merchant (or withdrawn); search removes it. Externalized to Kafka topic {@code catalogue.listing}, key = listing id. Schema: {@code resources/events/catalogue.listing_hidden.v1.schema.json}.
 * {@code aggregateId} is the listing id (offer or service), {@code kind} is {@code service} or {@code product}.
 */
@Externalized("catalogue.listing::#{aggregateId()}")
public record ListingHidden(String eventId, Instant occurredAt, String aggregateId, String merchantId, String kind)
        implements DomainEvent {}
