package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryRequests;
import ca.northline.fulfilment.api.RunChanged;
import ca.northline.fulfilment.application.DeliveryStore.Delivery;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.fulfilment.domain.DeliveryRules;
import ca.northline.shared.Ids;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeliveryRequests}: the orders module's hand-over, stored as {@code fulfilment.deliveries} (S-86); S-151: a
 * cancelled order or shop leaves its run, a failed delivery goes out again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class DeliveryRequestService implements DeliveryRequests {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ENDED = Set.of("delivered", "cancelled", "returned");

    private final DeliveryStore deliveries;
    private final RunStore runs;
    private final RunLifecycle lifecycle;
    private final ApplicationEventPublisher events;

    @Override
    public void request(Request r) {
        if (!r.kind().equals("pooled") && !r.kind().equals("direct")) {
            throw new IllegalArgumentException("delivery kind " + r.kind());
        }
        if (r.kind().equals("pooled") && r.window() == null) {
            throw new IllegalArgumentException("a pooled delivery needs its window: " + r.orderId());
        }
        var w = r.window();
        deliveries.insert(new Delivery(
                r.orderId(),
                r.orderRef(),
                r.orderType(),
                r.kind(),
                r.market(),
                w == null ? null : w.windowId(),
                w == null ? null : w.label(),
                w == null ? null : w.startsAt(),
                w == null ? null : w.endsAt(),
                w == null ? null : w.orderBy(),
                w == null ? null : w.packBy(),
                r.readyBy(),
                r.customerId(),
                r.dropoff(),
                DeliveryRules.pin(RANDOM),
                "waiting",
                null,
                List.of()));
        r.pickups().forEach(m -> deliveries.addPickup(r.orderId(), m));
        var age = r.idCheckAge();
        if (age != null) {
            deliveries.idCheck(r.orderId(), age, r.idCheckProvince());
        }
    }

    @Override
    public void packed(String orderId, String merchantId, Instant at) {
        deliveries.packed(orderId, merchantId, at);
    }

    @Override
    public void readyBy(String orderId, Instant readyBy) {
        deliveries.readyBy(orderId, readyBy);
    }

    @Override
    public void cancel(String orderId, Instant at) {
        var d = deliveries.find(orderId).orElse(null);
        if (d == null || ENDED.contains(d.state())) {
            return;
        }
        var runId = d.runId();
        var run = runId == null ? null : runs.lock(runId).orElse(null);
        if (run == null || d.state().equals("failed")) {
            // no run yet — or a failed delivery whose goods are already on their way back to the shops
            deliveries.moveState(orderId, "cancelled", at);
            return;
        }
        var mine = runs.stops(run.id()).stream()
                .filter(st -> st.orderId().equals(orderId))
                .toList();
        var pending = mine.stream()
                .filter(st -> !st.state().equals("done")
                        && (st.kind().equals("pickup") || st.kind().equals("dropoff")))
                .map(Stop::id)
                .toList();
        var carried = mine.stream()
                .filter(st -> st.kind().equals("pickup") && st.state().equals("done"))
                .map(Stop::merchantId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        runs.cancelStops(pending);
        // what the courier already carries goes back to its shops
        carried.forEach(m -> runs.addStop(run.id(), orderId, "return", m, null, at));
        deliveries.moveState(orderId, carried.isEmpty() ? "cancelled" : "returning", at);
        notifyCourier(run, at);
        lifecycle.settle(run, at);
        log.info(
                "Delivery of order {} cancelled ({} stops off run {}, {} returns)",
                orderId,
                pending.size(),
                run.id(),
                carried.size());
    }

    @Override
    public void dropPickup(String orderId, String merchantId, Instant at) {
        var d = deliveries.find(orderId).orElse(null);
        if (d == null || ENDED.contains(d.state())) {
            return;
        }
        var runId = d.runId();
        var run = runId == null ? null : runs.lock(runId).orElse(null);
        if (run != null) {
            var pickup = runs.stops(run.id()).stream()
                    .filter(st -> st.orderId().equals(orderId)
                            && st.kind().equals("pickup")
                            && merchantId.equals(st.merchantId()))
                    .toList();
            if (pickup.stream().anyMatch(st -> st.state().equals("done"))) {
                return; // already with the courier
            }
            runs.cancelStops(pickup.stream().map(Stop::id).toList());
            notifyCourier(run, at);
            lifecycle.settle(run, at);
        }
        deliveries.removePickup(orderId, merchantId);
    }

    @Override
    public void redeliver(String orderId, @Nullable Window window, Instant at) {
        deliveries.redeliver(orderId, window, at);
    }

    private void notifyCourier(RunStore.Run run, Instant at) {
        var courierId = run.courierId();
        if (courierId != null && !runs.stops(run.id()).isEmpty()) {
            events.publishEvent(new RunChanged(Ids.next(), at, run.id(), courierId, "stops_removed"));
        }
    }
}
