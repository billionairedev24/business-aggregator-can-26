package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-151: a courier brought a return back to its shop; the orders module counts it received and refunds it. In-process
 * only; ids.
 *
 * @param aggregateId the return ({@code orders.returns.id})
 */
public record ReturnDelivered(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String orderId,
        String merchantId,
        @Nullable String runId)
        implements DomainEvent {}
