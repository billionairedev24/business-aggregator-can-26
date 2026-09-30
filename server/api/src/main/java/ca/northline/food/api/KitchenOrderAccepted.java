package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import ca.northline.shared.EventType;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.accepted} — the kitchen tapped "Accept · start cooking" on Live orders; the customer app shows "cooking"
 * with the ETA. The orders module moves the order to {@code accepted}. Topic {@code orders.order}, key = order id. No PII.
 *
 * @param prepMin minutes promised (default prep + busy bump + longest item prep + large-order extra)
 * @param readyBy when the food should be ready; the courier is dispatched to arrive then
 */
@EventType("orders.order_accepted")
@Externalized("orders.order::#{aggregateId()}")
public record KitchenOrderAccepted(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String actorId,
        int prepMin,
        Instant readyBy)
        implements DomainEvent {}
