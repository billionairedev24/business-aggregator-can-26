package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryCompleted;
import ca.northline.orders.api.OrderConfirmed;
import ca.northline.orders.api.OrderDelivered;
import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.orders.domain.OrderDelivery;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery and confirmation of an order (S-78). The courier's drop-off ({@code delivery.completed}) moves it to
 * {@code delivered} and publishes {@code order.delivered}; the customer's confirmation moves it to {@code confirmed}
 * and publishes {@code order.confirmed}. Escrow follows those events ({@link GoodsEscrowRelease}). The order row is
 * locked first, so a drop-off and a confirmation of the same order never interleave.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class OrderDeliveryService implements ConfirmDelivery {

    private final OrderDeliveries orders;
    private final TrackOrder track;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** The courier dropped the order off with proof. Replays and late events (already delivered) change nothing. */
    void delivered(DeliveryCompleted e) {
        var order = orders.lock(e.aggregateId()).orElse(null);
        if (order == null) {
            log.warn("delivery.completed for unknown order {}", e.aggregateId());
            return;
        }
        if (!OrderDelivery.deliverable(order.state())) {
            return;
        }
        orders.markDelivered(order.id(), e.proof(), e.occurredAt());
        events.publishEvent(new OrderDelivered(Ids.next(), e.occurredAt(), order.id(), order.type(), e.proof()));
    }

    @Override
    public OrderTracking confirm(String customerId, String orderId) {
        var order = orders.lock(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new NotFound("order", orderId));
        if (order.confirmedAt() == null && !"confirmed".equals(order.state())) {
            if (OrderDelivery.closed(order.state())) {
                throw new Conflict("order_closed", OrderDelivery.CLOSED);
            }
            if (!OrderDelivery.confirmable(order.state())) {
                throw new Conflict("not_delivered", OrderDelivery.NOT_DELIVERED);
            }
            var now = clock.instant();
            orders.markConfirmed(orderId, now);
            events.publishEvent(new OrderConfirmed(Ids.next(), now, orderId, order.type()));
        }
        return track.view(customerId, orderId);
    }
}
