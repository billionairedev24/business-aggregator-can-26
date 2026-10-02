package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code run.changed} — a courier's run changed after it was given to them (S-102: the courier app's "run changed"
 * push). Today the only change is {@code unassigned}: dispatch gave the run to another courier (the new courier gets
 * {@link DeliveryAssigned}). Kafka topic {@code fulfilment.run}, key = run id. Ids only.
 *
 * @param courierId {@code fulfilment.couriers.id} of the courier the change is for
 * @param change {@code unassigned}
 */
@Externalized("fulfilment.run::#{aggregateId()}")
public record RunChanged(String eventId, Instant occurredAt, String aggregateId, String courierId, String change)
        implements DomainEvent {}
