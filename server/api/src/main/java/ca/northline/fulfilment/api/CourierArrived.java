package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code delivery.courier_arrived} — the courier is at a shop or kitchen to pick the order up (S-88): the kitchen
 * display and the Studio's orders show "Courier is here" at once. Kafka topic {@code fulfilment.delivery}, key = order
 * id. Ids only.
 *
 * @param orderType {@code goods} | {@code food}
 */
@Externalized("fulfilment.delivery::#{aggregateId()}")
public record CourierArrived(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String orderType, String runId)
        implements DomainEvent {}
