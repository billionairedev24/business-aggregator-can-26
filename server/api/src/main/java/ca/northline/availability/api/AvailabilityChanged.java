package ca.northline.availability.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code availability.changed} — hours, rules, time off, holidays or bookability of a business changed; the search
 * projection recomputes {@code listings.next_slot}. Externalized to Kafka topic {@code availability.availability}, key
 * = merchant id. {@code what} is one of {@code hours}, {@code rules}, {@code time_off}, {@code holidays}, {@code team}.
 */
@Externalized("availability.availability::#{aggregateId()}")
public record AvailabilityChanged(String eventId, Instant occurredAt, String aggregateId, String actorId, String what)
        implements DomainEvent {}
