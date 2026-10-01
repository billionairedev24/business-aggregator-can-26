package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.Externalized;

/**
 * {@code delivery.assigned} — a courier on shift was given the run (S-86; ARCHITECTURE "delivery.assigned"). Kafka
 * topic {@code fulfilment.run}, key = run id. Ids only.
 *
 * @param courierId {@code fulfilment.couriers.id}
 * @param orderIds the orders on the run
 */
@Externalized("fulfilment.run::#{aggregateId()}")
public record DeliveryAssigned(
        String eventId, Instant occurredAt, String aggregateId, String courierId, List<String> orderIds)
        implements DomainEvent {

    public DeliveryAssigned {
        orderIds = List.copyOf(orderIds);
    }
}
