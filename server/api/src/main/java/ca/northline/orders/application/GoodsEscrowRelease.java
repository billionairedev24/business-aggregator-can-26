package ca.northline.orders.application;

import ca.northline.orders.api.OrderConfirmed;
import ca.northline.orders.api.OrderDelivered;
import ca.northline.payments.api.EscrowLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Goods escrow follows the order's delivery (S-78; CLAUDE.md: goods release 7 days after delivery). {@code
 * order.delivered} fulfils each line's escrow — capture, and the 7-day clock starts at the drop-off; {@code
 * order.confirmed} releases them at once. Both capture the delivery fee. A refund case or dispute opened in the window
 * puts a line's escrow on hold (payments), and the release job skips it until the case closes.
 *
 * <p>Wired here rather than in payments because orders already depends on payments (as food's
 * {@code KitchenEscrowRelease} does for hand-off). The order row is locked first, so the two never run interleaved for
 * one order; {@link EscrowLifecycle} is idempotent, so a retried publication is safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GoodsEscrowRelease {

    static final String LINE = "order_line";

    private final OrderDeliveries orders;
    private final EscrowLifecycle escrow;

    @ApplicationModuleListener
    void on(OrderDelivered e) {
        if (!"goods".equals(e.orderType()) || orders.lock(e.aggregateId()).isEmpty()) {
            return;
        }
        orders.escrowLines(e.aggregateId()).forEach(line -> {
            if (!escrow.fulfilledIfHeld(LINE, line, e.occurredAt())) {
                log.debug("Order {}: line {} holds no escrow", e.aggregateId(), line);
            }
        });
        escrow.captureDeliveryFee(e.aggregateId(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(OrderConfirmed e) {
        if (!"goods".equals(e.orderType()) || orders.lock(e.aggregateId()).isEmpty()) {
            return;
        }
        orders.escrowLines(e.aggregateId()).forEach(line -> escrow.confirmedIfHeld(LINE, line, e.occurredAt()));
        escrow.captureDeliveryFee(e.aggregateId(), e.occurredAt());
    }
}
