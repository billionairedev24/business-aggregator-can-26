package ca.northline.fulfilment.application;

import ca.northline.fulfilment.application.DeliveryStore.Delivery;
import ca.northline.fulfilment.application.DispatchUseCases.CourierRef;
import ca.northline.fulfilment.application.DispatchUseCases.Place;
import ca.northline.fulfilment.application.DispatchUseCases.RunSummary;
import ca.northline.fulfilment.application.DispatchUseCases.RunView;
import ca.northline.fulfilment.application.DispatchUseCases.StopView;
import ca.northline.fulfilment.application.RunStore.Run;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.PublicDirectory;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Builds the courier's and the console's views of runs (shop places, order refs, courier names). */
@Component
@RequiredArgsConstructor
class RunViews {

    /** A pending stop this far past its ETA makes the run late in the ops view. */
    static final Duration LATE = Duration.ofMinutes(15);

    private final DeliveryStore deliveries;
    private final CourierStore couriers;
    private final PublicDirectory directory;
    private final PersonDirectory people;
    private final Clock clock;

    RunView courierView(Run run, List<Stop> stops) {
        var orders = orders(stops.stream().map(Stop::orderId).distinct().toList());
        var places = new HashMap<String, @Nullable Place>();
        var out = stops.stream()
                .map(s -> stop(s, orders.get(s.orderId()), places))
                .toList();
        return new RunView(
                run.id(),
                run.label(),
                run.part(),
                run.kind(),
                run.state(),
                run.market(),
                run.startsAt(),
                run.endsAt(),
                out);
    }

    StopView stop(Stop s, @Nullable Delivery d, Map<String, @Nullable Place> places) {
        var merchantId = s.merchantId();
        var place = merchantId == null ? null : places.computeIfAbsent(merchantId, this::place);
        var packed = s.kind().equals("pickup")
                && d != null
                && d.pickups().stream().anyMatch(p -> p.merchantId().equals(merchantId) && p.packedAt() != null);
        return new StopView(
                s.id(),
                s.seq(),
                s.kind(),
                s.state(),
                s.orderId(),
                d == null ? null : d.orderRef(),
                s.eta(),
                s.arrivedAt(),
                s.doneAt(),
                place,
                s.kind().equals("dropoff") && d != null ? d.dropoff() : null,
                packed,
                s.proofKind());
    }

    private @Nullable Place place(String merchantId) {
        return directory
                .byId(merchantId)
                .map(b -> new Place(merchantId, b.displayName(), b.address(), b.lat(), b.lng()))
                .orElse(new Place(merchantId, "", null, null, null));
    }

    RunSummary summary(Run run, List<Stop> stops) {
        var now = clock.instant();
        var done = (int) stops.stream().filter(s -> s.state().equals("done")).count();
        var next = stops.stream()
                .filter(s -> !s.state().equals("done") && s.eta() != null)
                .map(s -> Objects.requireNonNull(s.eta()))
                .findFirst()
                .orElse(null);
        var courierId = run.courierId();
        CourierRef courier = null;
        if (courierId != null) {
            var c = couriers.find(courierId).orElse(null);
            if (c != null) {
                var person = people.people(List.of(c.userId())).get(c.userId());
                courier = new CourierRef(c.id(), c.userId(), person == null ? null : person.displayName());
            }
        }
        return new RunSummary(
                run.id(),
                run.label(),
                run.part(),
                run.market(),
                run.kind(),
                run.state(),
                run.startsAt(),
                run.endsAt(),
                run.packBy(),
                courier,
                (int) stops.stream().map(Stop::orderId).distinct().count(),
                done,
                stops.size(),
                next,
                next != null && now.isAfter(next.plus(LATE)),
                run.heuristic());
    }

    Map<String, Delivery> orders(Collection<String> orderIds) {
        var out = new HashMap<String, Delivery>();
        orderIds.forEach(id -> deliveries.find(id).ifPresent(d -> out.put(id, d)));
        return out;
    }
}
