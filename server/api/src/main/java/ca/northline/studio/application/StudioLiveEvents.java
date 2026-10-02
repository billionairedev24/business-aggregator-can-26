package ca.northline.studio.application;

import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import ca.northline.food.api.KitchenPaused;
import ca.northline.food.api.KitchenResumed;
import ca.northline.fulfilment.api.CourierArrived;
import ca.northline.fulfilment.api.DeliveryAssigned;
import ca.northline.messaging.api.MessageSent;
import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.studio.application.StudioLive.Signal;
import ca.northline.studio.application.StudioLive.Topic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-68: the domain events that change what a Studio live screen shows become signals on {@link StudioLive}, after the
 * change committed (module listeners run after commit), so the browser's refetch already sees it.
 *
 * <p>S-113: {@code northline.kds.ticket_delivery} — from a food order being placed to its signal reaching the kitchen
 * display's bus (outbox, listener and pub/sub publish), the KDS ticket delivery SLI (docs/runbooks/alerting.md).
 */
@Component
@RequiredArgsConstructor
class StudioLiveEvents {

    static final String TICKET_DELIVERY = "northline.kds.ticket_delivery";

    private final StudioLive live;
    private final MeterRegistry meters;
    private final Clock clock;

    @ApplicationModuleListener
    void on(MessageSent e) {
        live.signal(e.merchantId(), new Signal(Topic.MESSAGE, e.aggregateId()));
    }

    @ApplicationModuleListener
    void on(OrderPlaced e) {
        var food = "food".equals(e.orderType());
        live.signal(e.merchantId(), new Signal(food ? Topic.KITCHEN : Topic.ORDERS, e.aggregateId()));
        if (food) {
            var took = Duration.between(e.occurredAt(), clock.instant());
            Timer.builder(TICKET_DELIVERY).register(meters).record(took.isNegative() ? Duration.ZERO : took);
        }
    }

    @ApplicationModuleListener
    void on(OrderPacked e) {
        live.signal(e.merchantId(), new Signal(Topic.ORDERS, e.aggregateId()));
    }

    @ApplicationModuleListener
    void on(KitchenOrderAccepted e) {
        live.signal(e.merchantId(), new Signal(Topic.KITCHEN, e.aggregateId()));
    }

    @ApplicationModuleListener
    void on(KitchenOrderReady e) {
        live.signal(e.merchantId(), new Signal(Topic.KITCHEN, e.aggregateId()));
    }

    @ApplicationModuleListener
    void on(FoodOrderHandedOff e) {
        live.signal(e.merchantId(), new Signal(Topic.KITCHEN, e.aggregateId()));
    }

    /** The aggregate of a pause is the kitchen (merchant) itself. */
    @ApplicationModuleListener
    void on(KitchenPaused e) {
        live.signal(e.aggregateId(), new Signal(Topic.KITCHEN, null));
    }

    @ApplicationModuleListener
    void on(KitchenResumed e) {
        live.signal(e.aggregateId(), new Signal(Topic.KITCHEN, null));
    }

    /** S-88: a courier was given a run — the shops' and kitchens' screens show who is coming. */
    @ApplicationModuleListener
    void on(DeliveryAssigned e) {
        var topic = "food".equals(e.orderType()) ? Topic.KITCHEN : Topic.ORDERS;
        e.merchantIds().forEach(m -> live.signal(m, new Signal(topic, null)));
    }

    /** S-88: the courier is at the counter ("Courier is here"). */
    @ApplicationModuleListener
    void on(CourierArrived e) {
        live.signal(
                e.merchantId(),
                new Signal("food".equals(e.orderType()) ? Topic.KITCHEN : Topic.ORDERS, e.aggregateId()));
    }
}
