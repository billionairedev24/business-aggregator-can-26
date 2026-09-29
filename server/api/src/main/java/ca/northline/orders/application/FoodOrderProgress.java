package ca.northline.orders.application;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code orders.orders.state} in step with the kitchen display (food module): accepted → {@code accepted},
 * ready → {@code ready}, handed off → {@code picked_up} (courier) or {@code delivered} (pickup at the counter).
 * Idempotent: each move only applies from the states that precede it.
 */
@Component
@RequiredArgsConstructor
class FoodOrderProgress {

    private final FoodOrderStates orders;

    @ApplicationModuleListener
    void on(KitchenOrderAccepted e) {
        orders.move(e.aggregateId(), Set.of("placed"), "accepted", null);
    }

    @ApplicationModuleListener
    void on(KitchenOrderReady e) {
        orders.move(e.aggregateId(), Set.of("placed", "accepted", "packing"), "ready", null);
    }

    @ApplicationModuleListener
    void on(FoodOrderHandedOff e) {
        var from = Set.of("placed", "accepted", "packing", "ready");
        if ("pickup".equals(e.fulfilmentMode())) {
            orders.move(e.aggregateId(), from, "delivered", e.occurredAt());
        } else {
            orders.move(e.aggregateId(), from, "picked_up", null);
        }
    }
}
