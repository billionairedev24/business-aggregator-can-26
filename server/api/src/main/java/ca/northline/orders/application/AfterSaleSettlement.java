package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryFailed;
import ca.northline.fulfilment.api.ReturnCollected;
import ca.northline.fulfilment.api.ReturnDelivered;
import ca.northline.orders.api.OrderCancelled;
import ca.northline.orders.domain.AfterSale;
import ca.northline.payments.api.OrderRefunds;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-151: the money and the follow-ups of cancellations, failed deliveries and returns, after their transaction commits
 * (retried from the outbox; every step is idempotent). Wired from orders, not payments, because orders already depends
 * on payments ({@link GoodsEscrowRelease}'s reason).
 *
 * <ul>
 *   <li>{@code order.cancelled} → each cancelled line's escrow goes back to the customer at once (a hold voided, a
 *       charge refunded with its tax); a whole order's delivery fee hold is released — less the failed-delivery fee
 *       when a failed delivery was refunded.
 *   <li>fulfilment's {@code delivery.failed} → the order waits for the customer's choice ({@link FailedDeliveryService}).
 *   <li>fulfilment's {@code return collected / delivered} → the return moves on and is refunded once back at the shop.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AfterSaleSettlement {

    private final OrderRefunds refunds;
    private final AfterSaleStore store;
    private final FailedDeliveryService failures;
    private final ReturnService returns;

    @ApplicationModuleListener
    void on(OrderCancelled e) {
        var ref = store.find(e.aggregateId())
                .map(o -> Objects.requireNonNullElse(o.ref(), o.id()))
                .orElse(e.aggregateId());
        var failed = AfterSale.DELIVERY_FAILED.equals(e.reason());
        var why = failed ? "delivery_failed" : "order_cancelled";
        var what = failed
                ? "Order %s refunded after a failed delivery".formatted(ref)
                : "Order %s cancelled".formatted(ref);
        for (var line : e.lineIds()) {
            refunds.refundNow(GoodsEscrowRelease.LINE, line, why, what);
        }
        if (e.whole()) {
            var keep = failed
                    ? store.failure(e.aggregateId())
                            .map(AfterSaleStore.Failure::feeCents)
                            .orElse(0L)
                    : 0L;
            refunds.settleDeliveryFee(e.aggregateId(), keep, e.occurredAt());
        }
    }

    @ApplicationModuleListener
    void on(DeliveryFailed e) {
        failures.failed(e);
    }

    @ApplicationModuleListener
    void on(ReturnCollected e) {
        returns.collected(e.aggregateId(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(ReturnDelivered e) {
        returns.delivered(e.aggregateId(), e.occurredAt());
    }
}
