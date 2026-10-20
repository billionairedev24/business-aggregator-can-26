package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.RunChanged;
import ca.northline.fulfilment.application.EarningsStore.Earning;
import ca.northline.fulfilment.application.RunStore.Run;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.payments.api.CourierPay;
import ca.northline.region.api.CourierPayRules;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a run ends (S-151), shared by the courier's actions, cancellations and dispatch: a run with every remaining stop
 * done is {@code done} — its courier is free again and paid for it under the market's pay model; a run left without
 * any stop (every order on it cancelled) is {@code cancelled}, and its courier told ({@code run.changed}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RunLifecycle {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> DELIVERED = Set.of("photo", "signature", "pin");
    private static final Set<String> NOT_HANDED_OVER = Set.of("failed", "id_refused");

    private final RunStore runs;
    private final CourierStore couriers;
    private final EarningsStore earnings;
    private final CourierPayRules payRules;
    private final CourierPay pay;
    private final FulfilmentProperties props;
    private final ApplicationEventPublisher events;

    /** After a stop changed on the run (it must be locked by the caller). */
    void settle(Run run, Instant at) {
        if (run.state().equals("done") || run.state().equals("cancelled")) {
            return;
        }
        var stops = runs.stops(run.id());
        if (stops.isEmpty()) {
            runs.moveState(run.id(), "cancelled", at);
            var courierId = run.courierId();
            if (courierId != null) {
                free(courierId);
                events.publishEvent(new RunChanged(Ids.next(), at, run.id(), courierId, "cancelled"));
            }
            log.info("Run {} cancelled: no stop left", run.id());
            return;
        }
        if (stops.stream().allMatch(s -> s.state().equals("done"))) {
            runs.moveState(run.id(), "done", at);
            var courierId = run.courierId();
            if (courierId != null) {
                free(courierId);
                earn(run, courierId, stops, at);
            }
        }
    }

    /** The courier is available again while on shift, else offline. */
    void free(String courierId) {
        couriers.status(courierId, couriers.onShift(courierId).isPresent() ? "available" : "offline");
    }

    /** The run's pay: a base when any stop was made, plus each kind of stop done. Posted once per run. */
    private void earn(Run run, String courierId, List<Stop> stops, Instant at) {
        var courier = couriers.find(courierId).orElse(null);
        if (courier == null) {
            return;
        }
        var model = props.courierPay().with(payRules.forMarket(run.market()));
        var pickups = places(stops, s -> s.kind().equals("pickup"));
        var dropoffs = count(stops, s -> s.kind().equals("dropoff") && DELIVERED.contains(proof(s)));
        var failed = count(stops, s -> s.kind().equals("dropoff") && NOT_HANDED_OVER.contains(proof(s)));
        var collects = count(stops, s -> s.kind().equals("collect"));
        var returns = places(stops, s -> s.kind().equals("return"));
        var any = pickups + dropoffs + failed + collects + returns > 0;
        var stopsCents = pickups * model.perPickupCents()
                + dropoffs * model.perDropoffCents()
                + failed * model.perFailedCents()
                + collects * model.perCollectCents()
                + returns * model.perReturnCents();
        var earning = new Earning(
                run.id(),
                courierId,
                courier.userId(),
                run.market(),
                pickups,
                dropoffs,
                failed,
                collects,
                returns,
                any ? model.perRunCents() : 0,
                stopsCents,
                JSON.writeValueAsString(model),
                at);
        if (earnings.insert(earning) && earning.totalCents() > 0) {
            pay.earned(run.id(), courier.userId(), earning.totalCents(), at);
            log.info("Run {}: courier {} earned {} cents", run.id(), courierId, earning.totalCents());
        }
    }

    private static String proof(Stop s) {
        return Objects.requireNonNullElse(s.proofKind(), "");
    }

    private static int count(List<Stop> stops, Predicate<Stop> kind) {
        return (int) stops.stream()
                .filter(s -> s.state().equals("done"))
                .filter(kind)
                .count();
    }

    /** Several orders at one shop are one place (one leg, one dwell — S-86's planner). */
    private static int places(List<Stop> stops, Predicate<Stop> kind) {
        return (int) stops.stream()
                .filter(s -> s.state().equals("done"))
                .filter(kind)
                .map(s -> Objects.requireNonNullElse(s.merchantId(), s.id()))
                .distinct()
                .count();
    }
}
