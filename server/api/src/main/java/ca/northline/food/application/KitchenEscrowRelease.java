package ca.northline.food.application;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.payments.api.EscrowLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Food escrow releases on handoff (CLAUDE.md): every line of the kitchen's part of the order is marked fulfilled.
 * Lines without an escrow (no authorized payment) are skipped; {@link EscrowLifecycle} is idempotent for retries.
 */
@Component
@RequiredArgsConstructor
class KitchenEscrowRelease {

    /** {@code refType} of the consumer checkout's escrow (orders module, S-57). */
    static final String FOOD_ORDER = "food_order";

    private final EscrowLifecycle escrow;
    private final KitchenOrderLines lines;

    @ApplicationModuleListener
    void on(FoodOrderHandedOff event) {
        lines.lineIds(event.merchantId(), event.aggregateId())
                .forEach(line -> escrow.fulfilledIfHeld("order_line", line, event.occurredAt()));
        // S-57: the consumer food checkout holds one escrow per food order (one card payment, one kitchen)
        escrow.fulfilledIfHeld(FOOD_ORDER, event.aggregateId(), event.occurredAt());
    }
}
