package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryStatuses;
import ca.northline.fulfilment.application.RunStore.Stop;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link DeliveryStatuses} from the delivery, its run and the run's stops. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DeliveryStatusService implements DeliveryStatuses {

    private final DeliveryStore deliveries;
    private final RunStore runs;
    private final CourierStore couriers;

    @Override
    public Optional<Status> of(String orderId) {
        return deliveries.find(orderId).map(d -> {
            var runId = d.runId();
            var run = runId == null ? null : runs.find(runId).orElse(null);
            if (run == null) {
                return new Status(orderId, d.state(), null, null, null, null, null, 0, null, null, d.pin());
            }
            var stops = runs.stops(run.id());
            var drop = stops.stream()
                    .filter(s -> s.orderId().equals(orderId) && s.kind().equals("dropoff"))
                    .findFirst();
            var before = drop.map(s -> (int) stops.stream()
                            .filter(o -> o.seq() < s.seq() && !o.state().equals("done"))
                            .count())
                    .orElse(0);
            var courierId = run.courierId();
            var courierUser = courierId == null
                    ? null
                    : couriers.find(courierId).map(CourierStore.Courier::userId).orElse(null);
            return new Status(
                    orderId,
                    d.state(),
                    run.id(),
                    run.label(),
                    run.state(),
                    courierUser,
                    drop.map(Stop::eta).orElse(null),
                    before,
                    drop.map(Stop::doneAt).orElse(null),
                    drop.map(Stop::proofKind).orElse(null),
                    d.pin());
        });
    }
}
