package ca.northline.catalogue.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code catalogue.instant_book_changed} (S-156) — whether a business's services book instantly changed (its own switch
 * or Northline's pause). Kafka topic {@code catalogue.listing} (no new topic), key = the business id: the search
 * indexer re-reads every document of the business. Schema
 * {@code resources/events/catalogue.instant_book_changed.v1.schema.json}.
 *
 * @param aggregateId the business id
 * @param effective whether customers can book instantly now
 */
@Externalized("catalogue.listing::#{aggregateId()}")
public record InstantBookChanged(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, boolean effective)
        implements DomainEvent {}
