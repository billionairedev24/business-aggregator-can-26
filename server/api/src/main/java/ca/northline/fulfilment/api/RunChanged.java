package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code run.changed} — a courier's run changed after it was given to them (S-102: the courier app's "run changed"
 * push): {@code unassigned} — dispatch gave the run to another courier (the new courier gets
 * {@link DeliveryAssigned}); S-151: {@code cancelled} — every order on it was cancelled; {@code stops_removed} — an
 * order was cancelled and its stops left the run; {@code stops_moved} — dispatch moved the stops not picked up yet to
 * another courier. Kafka topic {@code fulfilment.run}, key = run id. Ids only.
 *
 * @param courierId {@code fulfilment.couriers.id} of the courier the change is for
 * @param change {@code unassigned} | {@code cancelled} | {@code stops_removed} | {@code stops_moved}
 */
@Externalized("fulfilment.run::#{aggregateId()}")
public record RunChanged(String eventId, Instant occurredAt, String aggregateId, String courierId, String change)
        implements DomainEvent {}
