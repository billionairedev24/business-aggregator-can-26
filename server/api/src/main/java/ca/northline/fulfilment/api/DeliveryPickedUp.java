package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code delivery.picked_up} — the courier collected the order from every shop on it (S-86): the orders module moves
 * it to {@code picked_up}. Kafka topic {@code fulfilment.delivery}, key = order id. Ids only.
 */
@Externalized("fulfilment.delivery::#{aggregateId()}")
public record DeliveryPickedUp(String eventId, Instant occurredAt, String aggregateId, String runId, String courierId)
        implements DomainEvent {}
