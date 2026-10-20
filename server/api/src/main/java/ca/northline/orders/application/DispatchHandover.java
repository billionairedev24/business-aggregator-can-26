package ca.northline.orders.application;

import ca.northline.food.api.KitchenOrderAccepted;
import ca.northline.food.api.KitchenOrderReady;
import ca.northline.fulfilment.api.DeliveryAssigned;
import ca.northline.fulfilment.api.DeliveryPickedUp;
import ca.northline.fulfilment.api.DeliveryRequests;
import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import ca.northline.fulfilment.api.DeliveryRequests.Window;
import ca.northline.identity.api.DeliveryAddresses;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.OrderCancelled;
import ca.northline.orders.api.OrderPacked;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.region.api.MerchantPlaces;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hands orders to fulfilment (S-86) and follows its events. orders depends on fulfilment, never the reverse, so the
 * hand-over is a call into {@code fulfilment.api.DeliveryRequests} from these listeners:
 *
 * <ul>
 *   <li>{@code order.placed} (once per shop) → a delivery request: a pooled order with its run's window, a direct
 *       goods order, or a food delivery; pickups are never sent;
 *   <li>{@code order.packed} → that shop has packed; the kitchen's {@code order.ready} → the food is ready;
 *   <li>the kitchen's {@code order.accepted} → the food's ready-by time (the courier is sent to arrive then);
 *   <li>fulfilment's {@code delivery.picked_up} → the order is {@code picked_up}; {@code delivery.assigned} and
 *       {@code delivery.picked_up} also wake the customer's tracking stream;
 *   <li>S-151: {@code order.cancelled} → the whole delivery leaves its run, or one shop's pickup does.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DispatchHandover {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeliveryRequests requests;
    private final DispatchFacts facts;
    private final DeliveryRuns runs;
    private final DeliveryAddresses addresses;
    private final MerchantPlaces places;
    private final TrackingBus bus;
    private final AfterSaleStore afterSale;

    @ApplicationModuleListener
    void on(OrderPlaced e) {
        if (e.delivery().equals("pickup")) {
            return;
        }
        var f = facts.of(e.aggregateId()).orElse(null);
        if (f == null) {
            log.warn("order.placed for unknown order {}", e.aggregateId());
            return;
        }
        var request = e.orderType().equals("food") ? food(e, f) : goods(e, f);
        if (request != null) {
            requests.request(request);
            cancelledMeanwhile(e);
        }
    }

    /**
     * S-151: a cancellation may have been handled before this hand-over (listeners run in any order): the delivery,
     * or this shop's pickup, leaves again at once.
     */
    private void cancelledMeanwhile(OrderPlaced e) {
        var order = afterSale.find(e.aggregateId()).orElse(null);
        if (order == null) {
            return;
        }
        if (Set.of("cancelled", "refunded").contains(order.state())) {
            requests.cancel(e.aggregateId(), e.occurredAt());
            return;
        }
        var shop = afterSale.lines(e.aggregateId()).stream()
                .filter(l -> l.merchantId().equals(e.merchantId()))
                .toList();
        if (!shop.isEmpty() && shop.stream().allMatch(l -> l.state().equals("cancelled"))) {
            requests.dropPickup(e.aggregateId(), e.merchantId(), e.occurredAt());
        }
    }

    private DeliveryRequests.@Nullable Request goods(OrderPlaced e, DispatchFacts.Facts f) {
        Window window = null;
        String market;
        if (e.delivery().equals("pooled")) {
            var run = runs.run(Objects.requireNonNull(e.windowId())).orElse(null);
            if (run == null) {
                log.warn("Order {}: run window {} not found; not dispatched", e.aggregateId(), e.windowId());
                return null;
            }
            // a window without a market (the dev seed's R-611/R-612) serves every market: the order's city decides
            market = run.market().isBlank() ? marketOf(f) : run.market();
            window = new Window(run.windowId(), run.label(), run.startsAt(), run.endsAt(), run.orderBy(), run.packBy());
        } else {
            market = marketOf(f);
        }
        var address = f.customerId() == null || f.addressId() == null
                ? null
                : addresses.find(f.customerId(), f.addressId()).orElse(null);
        var dropoff = address == null
                ? new Dropoff("", null, f.deliveryArea(), null, null, null, null)
                : new Dropoff(
                        address.street(), address.unit(), address.city(), address.postal(), address.note(), null, null);
        return new DeliveryRequests.Request(
                e.aggregateId(),
                e.orderRef(),
                "goods",
                window == null ? "direct" : "pooled",
                market,
                window,
                List.of(e.merchantId()),
                dropoff,
                f.customerId(),
                null,
                f.idCheckAge(),
                f.province());
    }

    private String marketOf(DispatchFacts.Facts f) {
        var area = Objects.requireNonNullElse(f.deliveryArea(), "");
        return runs.market(area).orElse(area);
    }

    private DeliveryRequests.@Nullable Request food(OrderPlaced e, DispatchFacts.Facts f) {
        var json = f.foodDelivery();
        if (json == null) {
            return null; // pickup at the counter
        }
        JsonNode d = JSON.readTree(json);
        var city = text(d, "city");
        var market = city != null
                ? runs.market(city).orElse(city)
                : places.of(e.merchantId()).city();
        var note = text(d, "note");
        var dropoff = new Dropoff(
                Objects.requireNonNullElse(text(d, "street"), ""),
                text(d, "unit"),
                city,
                text(d, "postalCode"),
                note,
                d.path("lat").isNumber() ? d.path("lat").asDouble() : null,
                d.path("lng").isNumber() ? d.path("lng").asDouble() : null);
        return new DeliveryRequests.Request(
                e.aggregateId(),
                e.orderRef(),
                "food",
                "direct",
                Objects.requireNonNullElse(market, ""),
                null,
                List.of(e.merchantId()),
                dropoff,
                f.customerId(),
                null,
                f.idCheckAge(),
                f.province());
    }

    private static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        return v.isString() && !v.asString().isBlank() ? v.asString() : null;
    }

    @ApplicationModuleListener
    void on(OrderPacked e) {
        requests.packed(e.aggregateId(), e.merchantId(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(KitchenOrderAccepted e) {
        requests.readyBy(e.aggregateId(), e.readyBy());
    }

    @ApplicationModuleListener
    void on(KitchenOrderReady e) {
        requests.packed(e.aggregateId(), e.merchantId(), e.occurredAt());
    }

    @ApplicationModuleListener
    void on(DeliveryPickedUp e) {
        facts.pickedUp(e.aggregateId());
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(OrderCancelled e) {
        if (e.whole()) {
            requests.cancel(e.aggregateId(), e.occurredAt());
        } else {
            e.merchantIds().forEach(m -> requests.dropPickup(e.aggregateId(), m, e.occurredAt()));
        }
        bus.changed(e.aggregateId());
    }

    @ApplicationModuleListener
    void on(DeliveryAssigned e) {
        e.orderIds().forEach(bus::changed);
    }
}
