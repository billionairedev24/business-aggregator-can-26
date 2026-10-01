package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.CourierLocations;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.fulfilment.domain.RoutePlanner;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CourierLocations} (S-88): the courier's latest position for an order that is on its way — picked up, not yet
 * delivered — and the drop-off ETA from it: the legs from the position through the drop-offs still before this one
 * (the run's own leg rules, {@link RoutePlanner}), each earlier door adding its dwell. Without a recent position the
 * run's planned ETA stands.
 */
@Service
@Transactional(readOnly = true)
class LiveTrackingService implements CourierLocations {

    private final DeliveryStore deliveries;
    private final RunStore runs;
    private final CourierStore couriers;
    private final LivePositions live;
    private final Clock clock;
    private final RoutePlanner planner;

    LiveTrackingService(
            DeliveryStore deliveries,
            RunStore runs,
            CourierStore couriers,
            LivePositions live,
            Clock clock,
            FulfilmentProperties props) {
        this.deliveries = deliveries;
        this.runs = runs;
        this.couriers = couriers;
        this.live = live;
        this.clock = clock;
        this.planner = new RoutePlanner(new RoutePlanner.Settings(
                props.minutesPerKm(), props.minLeg(), props.unknownLeg(), props.pickupDwell(), props.dropoffDwell()));
    }

    @Override
    public Optional<Live> forOrder(String orderId) {
        var delivery = deliveries.find(orderId).filter(d -> d.state().equals("picked_up")).orElse(null);
        var runId = delivery == null ? null : delivery.runId();
        var run = runId == null ? null : runs.find(runId).orElse(null);
        var courierId = run == null ? null : run.courierId();
        if (run == null || courierId == null) {
            return Optional.empty();
        }
        var stops = runs.stops(run.id());
        var target = stops.stream()
                .filter(s -> s.orderId().equals(orderId) && s.kind().equals("dropoff"))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        var before = stops.stream()
                .filter(s -> s.kind().equals("dropoff") && s.seq() < target.seq() && !s.state().equals("done"))
                .toList();
        var position = live.latest(courierId).orElse(null);
        var user = couriers.find(courierId).map(CourierStore.Courier::userId).orElse(null);
        return Optional.of(new Live(user, position, eta(position, before, target), before.size()));
    }

    private @Nullable Instant eta(@Nullable Position position, java.util.List<Stop> before, Stop target) {
        if (position == null) {
            return target.eta();
        }
        var at = clock.instant();
        @Nullable Double lat = position.lat();
        @Nullable Double lng = position.lng();
        for (var stop : before) {
            var to = coordinates(stop);
            at = at.plus(planner.leg(lat, lng, to.lat(), to.lng())).plus(planner.dropoffDwell());
            lat = to.lat();
            lng = to.lng();
        }
        var to = coordinates(target);
        return at.plus(planner.leg(lat, lng, to.lat(), to.lng()));
    }

    private record Point(@Nullable Double lat, @Nullable Double lng) {}

    private Point coordinates(Stop stop) {
        var drop = deliveries.find(stop.orderId()).map(DeliveryStore.Delivery::dropoff).orElse(null);
        return drop == null ? new Point(null, null) : new Point(drop.lat(), drop.lng());
    }

    @Override
    public Subscription subscribe(String orderId, Runnable onMove) {
        return live.subscribe(orderId, onMove);
    }
}
