package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code delivery.completed} — the courier dropped an order off with proof (S-78/S-86). The orders module moves the
 * order to {@code delivered}, which starts the goods escrow's 7-day release window (CLAUDE.md) and captures the
 * delivery fee. Kafka topic {@code fulfilment.delivery}, key = order id. Ids only: no address, no photo.
 *
 * @param aggregateId the order ({@code orders.orders.id})
 * @param runId the courier's run ({@code fulfilment.runs.id}), when the drop-off was on one
 * @param stopId the drop-off stop ({@code fulfilment.stops.id}), when the drop-off was on one
 * @param courierId the courier ({@code fulfilment.couriers.id}), when known
 * @param proof {@code photo} | {@code signature} | {@code pin}
 */
@Externalized("fulfilment.delivery::#{aggregateId()}")
public record DeliveryCompleted(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        @Nullable String runId,
        @Nullable String stopId,
        @Nullable String courierId,
        String proof)
        implements DomainEvent {}
