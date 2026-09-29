package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * {@code listing.submitted} — A draft (or rejected) listing was submitted for vetting; automated checks run next. Not externalized. Schema: {@code resources/events/catalogue.listing_submitted.v1.schema.json}.
 * {@code aggregateId} is the listing id (offer or service), {@code kind} is {@code service} or {@code product}.
 */
public record ListingSubmitted(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String kind, String actorId)
        implements DomainEvent {}
