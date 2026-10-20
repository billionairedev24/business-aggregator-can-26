package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-151: a courier collected a return at the customer's door; the orders module moves it to {@code collected}.
 * In-process only; ids.
 *
 * @param aggregateId the return ({@code orders.returns.id})
 */
public record ReturnCollected(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String orderId,
        @Nullable String runId) implements DomainEvent {}
