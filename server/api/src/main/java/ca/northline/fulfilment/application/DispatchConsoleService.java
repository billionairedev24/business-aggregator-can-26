package ca.northline.fulfilment.application;

import ca.northline.fulfilment.application.CourierStore.Courier;
import ca.northline.fulfilment.application.CourierStore.Shift;
import ca.northline.fulfilment.application.DispatchUseCases.AssignCouriers;
import ca.northline.fulfilment.application.DispatchUseCases.CourierSummary;
import ca.northline.fulfilment.application.DispatchUseCases.DeliveryView;
import ca.northline.fulfilment.application.DispatchUseCases.DispatchConsole;
import ca.northline.fulfilment.application.DispatchUseCases.PickupView;
import ca.northline.fulfilment.application.DispatchUseCases.PlanRuns;
import ca.northline.fulfilment.application.DispatchUseCases.Planned;
import ca.northline.fulfilment.application.DispatchUseCases.RunDetail;
import ca.northline.fulfilment.application.DispatchUseCases.RunSummary;
import ca.northline.fulfilment.application.DispatchUseCases.ShiftView;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.fulfilment.domain.DeliveryRules;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's dispatch view and actions (S-86 for S-81's orders monitor): runs by market and day, a run's stops, an
 * order's delivery, couriers and their shifts, onboarding a courier, scheduling a shift, planning now and assigning a
 * run by hand. Staff only ({@code /api/v1/console/**}: role staff + second factor).
 */
@Service
@RequiredArgsConstructor
@Transactional
class DispatchConsoleService implements DispatchConsole {

    static final Duration MAX_SHIFT = Duration.ofHours(12);

    private final RunStore runs;
    private final DeliveryStore deliveries;
    private final CourierStore couriers;
    private final RunViews views;
    private final DispatchService dispatch;
    private final PlanRuns planRuns;
    private final AssignCouriers assignCouriers;
    private final PersonDirectory people;
    private final BusinessNames names;
    private final Clock clock;
    private final LivePositions live;

    @Override
    @Transactional(readOnly = true)
    public List<RunSummary> runs(@Nullable String market, Instant from, Instant to) {
        var list = runs.runs(market, from, to);
        var stops = new HashMap<String, List<Stop>>();
        runs.stops(list.stream().map(RunStore.Run::id).toList())
                .forEach(s -> stops.computeIfAbsent(s.runId(), _ -> new java.util.ArrayList<>())
                        .add(s));
        return list.stream()
                .map(r -> views.summary(r, stops.getOrDefault(r.id(), List.of())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RunDetail run(String runId) {
        var run = runs.find(runId).orElseThrow(() -> new NotFound("run", runId));
        var stops = runs.stops(runId);
        var view = views.courierView(run, stops);
        return new RunDetail(views.summary(run, stops), view.stops());
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryView delivery(String orderId) {
        var d = deliveries.find(orderId).orElseThrow(() -> new NotFound("delivery", orderId));
        var runId = d.runId();
        var run = runId == null ? null : runs.find(runId).orElse(null);
        var stops = run == null ? List.<Stop>of() : runs.stops(run.id());
        var mine = stops.stream().filter(s -> s.orderId().equals(orderId)).toList();
        var pickups = d.pickups().stream()
                .map(p -> new PickupView(
                        p.merchantId(),
                        names.displayName(p.merchantId()).orElse(null),
                        p.packedAt(),
                        mine.stream()
                                .filter(s -> s.kind().equals("pickup")
                                        && p.merchantId().equals(s.merchantId())
                                        && s.state().equals("done"))
                                .map(Stop::doneAt)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null)))
                .toList();
        var drop = mine.stream().filter(s -> s.kind().equals("dropoff")).findFirst();
        return new DeliveryView(
                d.orderId(),
                d.orderRef(),
                d.orderType(),
                d.kind(),
                d.market(),
                d.state(),
                d.orderBy(),
                d.packBy(),
                run == null ? null : views.summary(run, stops),
                pickups,
                drop.map(Stop::eta).orElse(null),
                drop.map(Stop::doneAt).orElse(null),
                drop.map(Stop::proofKind).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CourierSummary> couriers(@Nullable String market) {
        var list = couriers.inMarket(market);
        var persons = people.people(list.stream().map(Courier::userId).toList());
        return list.stream().map(c -> summary(c, persons.get(c.userId()))).toList();
    }

    @Override
    public CourierSummary addCourier(String userId, String market, String vehicle) {
        if (market.isBlank()) {
            throw RuleViolation.of("market", "required", DeliveryRules.MARKET_REQUIRED);
        }
        if (!DeliveryRules.VEHICLES.contains(vehicle)) {
            throw RuleViolation.of("vehicle", "required", DeliveryRules.VEHICLE);
        }
        var person = people.people(List.of(userId)).get(userId);
        if (person == null) {
            throw new NotFound("user", userId);
        }
        var courier = new Courier(Ids.next(), userId, market.strip(), vehicle, "offline", true, null);
        if (!couriers.insert(courier)) {
            throw new Conflict("already_a_courier", DeliveryRules.ALREADY_A_COURIER);
        }
        return summary(courier, person);
    }

    @Override
    public ShiftView addShift(String courierId, Instant startsAt, Instant endsAt) {
        if (couriers.find(courierId).isEmpty()) {
            throw new NotFound("courier", courierId);
        }
        if (!endsAt.isAfter(startsAt) || Duration.between(startsAt, endsAt).compareTo(MAX_SHIFT) > 0) {
            throw RuleViolation.of("endsAt", "range", DeliveryRules.SHIFT_TIMES);
        }
        var shift = new Shift(Ids.next(), courierId, startsAt, endsAt, "scheduled", null, null);
        couriers.insertShift(shift);
        return CourierAppService.view(shift);
    }

    /** A run that hasn't started goes to this courier (any previous courier is freed); the courier must be free. */
    @Override
    public RunSummary assign(String runId, String courierId) {
        var now = clock.instant();
        var run = runs.lock(runId).orElseThrow(() -> new NotFound("run", runId));
        if (couriers.find(courierId).isEmpty()) {
            throw new NotFound("courier", courierId);
        }
        if (!run.state().equals("planned")) {
            throw new Conflict("run_started", DeliveryRules.RUN_STARTED);
        }
        if (courierId.equals(run.courierId())) {
            return views.summary(run, runs.stops(runId));
        }
        if (!couriers.claim(courierId, now)) {
            throw new Conflict("courier_busy", DeliveryRules.COURIER_BUSY);
        }
        var previous = run.courierId();
        if (previous != null) {
            runs.unassign(runId);
            couriers.status(previous, couriers.onShift(previous).isPresent() ? "available" : "offline");
        }
        dispatch.give(run, courierId, now);
        return views.summary(runs.find(runId).orElseThrow(), runs.stops(runId));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Planned planNow(@Nullable String market) {
        var planned = planRuns.plan(market);
        return new Planned(planned, assignCouriers.assign(market));
    }

    private CourierSummary summary(Courier c, PersonDirectory.@Nullable Person person) {
        return new CourierSummary(
                c.id(),
                c.userId(),
                person == null ? null : person.displayName(),
                c.market(),
                c.vehicle(),
                c.status(),
                c.active(),
                couriers.onShift(c.id()).map(CourierAppService::view).orElse(null),
                runs.openRunOf(c.id()).map(RunStore.Run::id).orElse(null),
                live.latest(c.id()).orElse(null));
    }
}
