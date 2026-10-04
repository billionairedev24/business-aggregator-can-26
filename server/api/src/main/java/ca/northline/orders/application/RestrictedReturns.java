package ca.northline.orders.application;

import ca.northline.food.api.FoodOrderRefused;
import ca.northline.fulfilment.api.DeliveryRefused;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.CustomerEscrows;
import ca.northline.payments.api.EscrowLifecycle;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Age-restricted items not handed over (2026-10-04) — at the door ({@code fulfilment.DeliveryRefused}) or at the
 * kitchen's counter ({@code food.FoodOrderRefused}). The order becomes {@code returned} and is refunded by the rules
 * the owner decided (docs/runbooks/age-restricted.md § Refunds):
 *
 * <ul>
 *   <li><b>Goods</b> go back to the shop intact: every line is refunded in full (an uncaptured card hold is simply
 *       released); the <b>delivery fee is kept</b> — the courier made the trip, and the customer was told at checkout
 *       that photo ID is checked at the door.
 *   <li><b>Food</b> was cooked to order and can't be resold: only the <b>age-restricted dishes</b> are refunded; the
 *       rest of the order, the fees and the tip are charged. The kitchen keeps its alcohol (it never left its control
 *       for a pickup; the courier brings a delivery's back).
 * </ul>
 *
 * Refunds go through the refund queue as auto-approved cases ({@link CustomerCases#requestRefund}), so the card,
 * ledger, tax reversal and the customer's "Refunds &amp; cases" work as for any other refund. Idempotent: a retried event
 * finds the order already returned and does nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RestrictedReturns {

    static final String WHAT = "Not handed over: age check at handoff (%s)";

    private final OrderDeliveries orders;
    private final CustomerEscrows escrows;
    private final CustomerCases cases;
    private final EscrowLifecycle escrow;

    @ApplicationModuleListener
    void on(DeliveryRefused e) {
        returned(e.aggregateId(), e.reason(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(FoodOrderRefused e) {
        returned(e.aggregateId(), e.reason(), e.occurredAt());
    }

    private void returned(String orderId, String reason, Instant at) {
        var order = orders.lock(orderId).orElse(null);
        if (order == null || order.customerId() == null) {
            log.warn("Refused handoff of unknown order {}", orderId);
            return;
        }
        var restricted = orders.restrictedCents(orderId);
        var lines = orders.escrowLines(orderId);
        if (!orders.markReturned(orderId, !"food".equals(order.type()), at)) {
            return;
        }
        var customer = order.customerId();
        var what = WHAT.formatted(reason);
        if ("food".equals(order.type())) {
            escrows.of(customer, "food_order", List.of(orderId)).stream()
                    .filter(f -> "held".equals(f.state()) && !f.openCase())
                    .findFirst()
                    .ifPresent(f ->
                            cases.requestRefund(f.escrowId(), customer, Math.min(restricted, f.amountCents()), what));
            return;
        }
        for (var f : escrows.of(customer, GoodsEscrowRelease.LINE, lines)) {
            if ("held".equals(f.state()) && !f.openCase()) {
                cases.requestRefund(f.escrowId(), customer, f.amountCents(), what);
            }
        }
        escrow.captureDeliveryFee(orderId, at);
    }
}
