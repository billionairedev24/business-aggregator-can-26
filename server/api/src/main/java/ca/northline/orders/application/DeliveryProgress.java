package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** The courier's drop-off (fulfilment) delivers the order (S-78). */
@Component
@RequiredArgsConstructor
class DeliveryProgress {

    private final OrderDeliveryService deliveries;

    @ApplicationModuleListener
    void on(DeliveryCompleted e) {
        deliveries.delivered(e);
    }
}
