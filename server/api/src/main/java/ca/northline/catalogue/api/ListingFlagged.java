package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.Externalized;

/**
 * {@code listing.flagged} — Automated vetting found problems; the listing stays pending and joins the console vetting queue. Externalized to Kafka topic {@code catalogue.listing}, key = listing id. Schema: {@code resources/events/catalogue.listing_flagged.v1.schema.json}.
 * {@code aggregateId} is the listing id (offer or service), {@code kind} is {@code service} or {@code product}.
 */
@Externalized("catalogue.listing::#{aggregateId()}")
public record ListingFlagged(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String kind, List<String> flags)
        implements DomainEvent {}
