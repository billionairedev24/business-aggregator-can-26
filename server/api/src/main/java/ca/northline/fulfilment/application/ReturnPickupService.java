package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.ReturnPickups;
import ca.northline.fulfilment.api.RunChanged;
import ca.northline.fulfilment.application.ReturnPickupStore.ReturnPickup;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.shared.Ids;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReturnPickups} (S-151): a return waits here until the planner puts it on a run ({@link DispatchService}).
 * Withdrawing it before the courier collects it takes its stops off the run.
 */
@Service
@RequiredArgsConstructor
@Transactional
class ReturnPickupService implements ReturnPickups {

    private final ReturnPickupStore pickups;
    private final RunStore runs;
    private final RunLifecycle lifecycle;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public void request(Request r) {
        pickups.insert(new ReturnPickup(
                r.returnId(),
                r.returnRef(),
                r.orderId(),
                r.merchantId(),
                r.market(),
                r.customerId(),
                r.collectFrom(),
                "waiting",
                null,
                clock.instant()));
    }

    @Override
    public void cancel(String returnId) {
        var p = pickups.find(returnId).orElse(null);
        if (p == null || !(p.state().equals("waiting") || p.state().equals("planned"))) {
            return; // collected already: the courier brings it back to the shop
        }
        var now = clock.instant();
        var runId = p.runId();
        var run = runId == null ? null : runs.lock(runId).orElse(null);
        if (run != null) {
            var stops = runs.stops(run.id()).stream()
                    .filter(s -> returnId.equals(s.returnId()) && !s.state().equals("done"))
                    .map(Stop::id)
                    .toList();
            runs.cancelStops(stops);
            var courierId = run.courierId();
            if (courierId != null && !runs.stops(run.id()).isEmpty()) {
                events.publishEvent(new RunChanged(Ids.next(), now, run.id(), courierId, "stops_removed"));
            }
            lifecycle.settle(run, now);
        }
        pickups.moveState(returnId, "cancelled", now);
    }
}
