package ca.northline.orders.application;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.api.OrderPlaced;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Every order event that changes what the customer's tracking shows is a "changed" on the tracking bus (S-52). */
@Component
@RequiredArgsConstructor
class OrderTrackingEvents {

    private final TrackingBus bus;

    @ApplicationModuleListener
    void on(OrderPlaced e) {
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(OrderPacked e) {
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(KitchenOrderAccepted e) {
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(KitchenOrderReady e) {
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(FoodOrderHandedOff e) {
        bus.changed(e.aggregateId());
    }
}
