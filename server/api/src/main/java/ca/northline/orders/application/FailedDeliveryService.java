package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryFailed;
import ca.northline.fulfilment.api.DeliveryRequests;
import ca.northline.fulfilment.api.DeliveryRequests.Window;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.OrderDeliveryFailed;
import ca.northline.orders.application.AfterSaleStore.Failure;
import ca.northline.orders.application.AfterSaleUseCases.FailedDeliveries;
import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.orders.domain.AfterSale;
import ca.northline.orders.domain.OrderDelivery;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FailedDeliveries} (S-151). The courier couldn't hand the order over ({@code delivery.failed}): it becomes
 * {@code delivery_failed}, and the customer has {@code failed-delivery-decide-within} (48 h by default) to choose:
 *
 * <ul>
 *   <li><b>re-delivery</b> — on the market's next pooled run they can still make (a direct order goes out direct
 *       again); the shops still have it packed (the courier brought it back), so the order is {@code ready};
 *   <li><b>a refund</b> — everything back to the card, less the failed-delivery fee kept from the delivery fee
 *       (configuration, 0 by default); the order becomes {@code refunded}.
 * </ul>
 *
 * Without a choice in time it is refunded the same way ({@code auto_refund}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FailedDeliveryService implements FailedDeliveries {

    private final AfterSaleStore store;
    private final CancellationService cancellations;
    private final DeliveryRuns runs;
    private final DeliveryRequests requests;
    private final TrackOrder track;
    private final AfterSaleProperties props;
    private final ApplicationEventPublisher events;
    private final TrackingBus bus;
    private final Clock clock;

    /** fulfilment's {@code delivery.failed}. A replay (already failed for this attempt) changes nothing. */
    void failed(DeliveryFailed e) {
        var order = store.lock(e.aggregateId()).orElse(null);
        if (order == null || !OrderDelivery.deliverable(order.state())) {
            return;
        }
        var known = store.failure(order.id()).orElse(null);
        if (known != null && known.attempts() >= e.attempt() && order.state().equals("delivery_failed")) {
            return;
        }
        var decideBy = AfterSale.decideBy(e.occurredAt(), props.failedDeliveryDecideWithin());
        store.failed(new Failure(
                order.id(),
                e.reason(),
                e.occurredAt(),
                decideBy,
                e.attempt(),
                null,
                null,
                props.failedDeliveryFeeCents(),
                null,
                e.runId()));
        store.state(order.id(), "delivery_failed");
        events.publishEvent(new OrderDeliveryFailed(
                Ids.next(),
                e.occurredAt(),
                order.id(),
                e.reason(),
                decideBy,
                props.failedDeliveryFeeCents(),
                e.attempt()));
        bus.changed(order.id());
        log.info("Order {}: delivery failed ({}), the customer decides by {}", order.id(), e.reason(), decideBy);
    }

    @Override
    public OrderTracking choose(String customerId, String orderId, String choice) {
        if (!AfterSale.CHOICES.contains(choice)) {
            throw RuleViolation.of("choice", "required", AfterSale.CHOICE);
        }
        var order = store.lock(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new NotFound("order", orderId));
        var failure = store.failure(orderId).orElse(null);
        if (failure == null || !order.state().equals("delivery_failed")) {
            throw new Conflict("not_failed", AfterSale.NOT_FAILED);
        }
        if (failure.choice() != null) {
            throw new Conflict("already_chosen", AfterSale.ALREADY_CHOSEN);
        }
        var now = clock.instant();
        if (choice.equals("refund")) {
            store.chosen(orderId, "refund", now, null);
            cancellations.refundFailed(order, customerId, now);
        } else {
            var window = nextWindow(order);
            store.chosen(orderId, "redeliver", now, window == null ? null : window.windowId());
            store.state(orderId, "ready");
            requests.redeliver(orderId, window, now);
            bus.changed(orderId);
        }
        return track.view(customerId, orderId);
    }

    @Override
    public int refundDue() {
        var now = clock.instant();
        int n = 0;
        for (var f : store.due(now, 100)) {
            var order = store.lock(f.orderId()).orElse(null);
            if (order == null || !order.state().equals("delivery_failed")) {
                continue;
            }
            store.chosen(f.orderId(), "auto_refund", now, null);
            cancellations.refundFailed(order, null, now);
            n++;
        }
        return n;
    }

    /** The next pooled run of the order's market the customer can still make; null for a direct order. */
    private @Nullable Window nextWindow(AfterSaleStore.OrderFacts order) {
        if (!"pooled".equals(order.deliveryKind())) {
            return null;
        }
        var area = Objects.requireNonNullElse(order.deliveryArea(), "");
        var market = runs.market(area).orElse(area);
        var now = clock.instant();
        return runs.upcoming(market, now).stream()
                .filter(r -> r.openAt(now))
                .findFirst()
                .map(r -> new Window(r.windowId(), r.label(), r.startsAt(), r.endsAt(), r.orderBy(), r.packBy()))
                .orElseThrow(() -> new Conflict("no_run", AfterSale.NO_RUN));
    }
}
