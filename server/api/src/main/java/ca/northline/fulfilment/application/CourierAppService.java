package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.CourierArrived;
import ca.northline.fulfilment.api.CourierLocations;
import ca.northline.fulfilment.api.DeliveryCompleted;
import ca.northline.fulfilment.api.DeliveryPickedUp;
import ca.northline.fulfilment.api.DeliveryRefused;
import ca.northline.fulfilment.application.CourierStore.Courier;
import ca.northline.fulfilment.application.CourierStore.Shift;
import ca.northline.fulfilment.application.DeliveryStore.Delivery;
import ca.northline.fulfilment.application.DispatchUseCases.CourierApp;
import ca.northline.fulfilment.application.DispatchUseCases.CourierView;
import ca.northline.fulfilment.application.DispatchUseCases.IdCheckAnswer;
import ca.northline.fulfilment.application.DispatchUseCases.Ping;
import ca.northline.fulfilment.application.DispatchUseCases.RunView;
import ca.northline.fulfilment.application.DispatchUseCases.ShiftView;
import ca.northline.fulfilment.application.RunStore.Run;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.fulfilment.domain.DeliveryRules;
import ca.northline.restricted.api.HandoffChecks;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The courier app's use cases (S-86 for S-87). A courier sees and moves only their own run; the run row is locked on
 * every action, so two taps (or two phones) can't complete one stop twice.
 *
 * <ul>
 *   <li>Shifts: ops schedules them; the courier starts one from 15 minutes before its start (status
 *       {@code available}) and ends it when no run is open ({@code offline}).
 *   <li>Stops: arrive → pickup (the shop must have packed; the sealed-bag scan is recorded) → drop-off with proof: a
 *       photo or signature uploaded first, or the customer's 4-digit PIN. The last pickup of an order publishes
 *       {@code delivery.picked_up}; a drop-off {@code delivery.completed} (S-78: the goods escrow window starts).
 *   <li>The run is {@code loading} from the first stop, {@code en_route} once every pickup is done and {@code done}
 *       after the last drop-off; the courier is then {@code available} again (or {@code offline} off shift).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
class CourierAppService implements CourierApp {

    static final Duration EARLY_START = Duration.ofMinutes(15);

    private final CourierStore couriers;
    private final RunStore runs;
    private final DeliveryStore deliveries;
    private final ProofStorage proofs;
    private final RunViews views;
    private final LivePositions live;
    private final FulfilmentProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final HandoffChecks handoffs;

    @Override
    @Transactional(readOnly = true)
    public CourierView me(String userId) {
        var c = courier(userId);
        return new CourierView(
                c.id(),
                c.market(),
                c.vehicle(),
                c.status(),
                couriers.onShift(c.id()).map(CourierAppService::view).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftView> shifts(String userId) {
        var c = courier(userId);
        return couriers.shifts(c.id(), clock.instant()).stream()
                .map(CourierAppService::view)
                .toList();
    }

    @Override
    public ShiftView startShift(String userId, String shiftId) {
        var c = courier(userId);
        var shift = ownShift(c, shiftId);
        var now = clock.instant();
        if (shift.state().equals("on")) {
            return view(shift);
        }
        if (!shift.state().equals("scheduled")
                || now.isBefore(shift.startsAt().minus(EARLY_START))
                || !now.isBefore(shift.endsAt())
                || couriers.onShift(c.id()).isPresent()) {
            throw new Conflict("shift_not_startable", DeliveryRules.SHIFT_NOT_STARTABLE);
        }
        couriers.startShift(shiftId, now);
        if (runs.openRunOf(c.id()).isEmpty()) {
            couriers.status(c.id(), "available");
        }
        return view(couriers.shift(shiftId).orElseThrow());
    }

    @Override
    public ShiftView endShift(String userId, String shiftId) {
        var c = courier(userId);
        var shift = ownShift(c, shiftId);
        if (!shift.state().equals("on")) {
            return view(shift);
        }
        if (runs.openRunOf(c.id()).isPresent()) {
            throw new Conflict("run_open", DeliveryRules.RUN_OPEN);
        }
        couriers.endShift(shiftId, clock.instant());
        couriers.status(c.id(), "offline");
        return view(couriers.shift(shiftId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunView> myRun(String userId) {
        var c = courier(userId);
        return runs.openRunOf(c.id()).map(r -> views.courierView(r, runs.stops(r.id())));
    }

    @Override
    public RunView arrive(String userId, String stopId) {
        var at = clock.instant();
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.state().equals("done")) {
            runs.arrived(stopId, at);
            start(mine.run(), at);
            var merchantId = stop.merchantId();
            if (stop.kind().equals("pickup") && stop.arrivedAt() == null && merchantId != null) {
                events.publishEvent(new CourierArrived(
                        Ids.next(),
                        at,
                        stop.orderId(),
                        merchantId,
                        delivery(stop.orderId()).orderType(),
                        mine.run().id()));
            }
        }
        return reload(mine.run());
    }

    @Override
    public RunView pickUp(String userId, String stopId, boolean scanOk) {
        var at = clock.instant();
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.kind().equals("pickup")) {
            throw new Conflict("not_a_pickup", DeliveryRules.NOT_YOUR_RUN);
        }
        if (stop.state().equals("done")) {
            return reload(mine.run());
        }
        var delivery = delivery(stop.orderId());
        var packed = delivery.pickups().stream()
                .anyMatch(p -> p.merchantId().equals(stop.merchantId()) && p.packedAt() != null);
        if (!packed) {
            throw new Conflict("not_packed", DeliveryRules.NOT_PACKED);
        }
        runs.pickedUp(stopId, scanOk, at);
        start(mine.run(), at);
        var stops = runs.stops(mine.run().id());
        var orderPickups = stops.stream()
                .filter(s -> s.orderId().equals(stop.orderId()) && s.kind().equals("pickup"))
                .toList();
        if (orderPickups.stream().allMatch(s -> s.state().equals("done"))) {
            deliveries.moveState(stop.orderId(), "picked_up", at);
            events.publishEvent(new DeliveryPickedUp(
                    Ids.next(),
                    at,
                    stop.orderId(),
                    mine.run().id(),
                    mine.courier().id()));
        }
        if (stops.stream()
                .filter(s -> s.kind().equals("pickup"))
                .allMatch(s -> s.state().equals("done"))) {
            runs.moveState(mine.run().id(), "en_route", at);
        }
        return reload(mine.run());
    }

    @Override
    public RunView proof(String userId, String stopId, String kind, Bytes file) {
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.kind().equals("dropoff")) {
            throw new Conflict("not_a_dropoff", DeliveryRules.NOT_YOUR_RUN);
        }
        if (stop.state().equals("done")) {
            throw new Conflict("stop_done", DeliveryRules.STOP_DONE);
        }
        if (!kind.equals("photo") && !kind.equals("signature")) {
            throw RuleViolation.of("kind", "required", DeliveryRules.PROOF_REQUIRED);
        }
        var bytes = file.toArray();
        var type = DeliveryRules.imageType(bytes);
        if (type == null || bytes.length > DeliveryRules.MAX_PROOF_BYTES) {
            throw RuleViolation.of("file", "format", DeliveryRules.PROOF_FILE);
        }
        var key = mine.run().id() + "/" + stopId + "-" + kind;
        proofs.put(key, bytes, type);
        runs.proofStored(stopId, kind, key);
        return reload(mine.run());
    }

    @Override
    public RunView dropOff(
            String userId, String stopId, String proof, @Nullable String pin, @Nullable IdCheckAnswer idCheck) {
        var at = clock.instant();
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.kind().equals("dropoff")) {
            throw new Conflict("not_a_dropoff", DeliveryRules.NOT_YOUR_RUN);
        }
        if (stop.state().equals("done")) {
            return reload(mine.run());
        }
        if (!DeliveryRules.PROOFS.contains(proof)) {
            throw RuleViolation.of("proof", "required", DeliveryRules.PROOF_REQUIRED);
        }
        var delivery = delivery(stop.orderId());
        var stops = runs.stops(mine.run().id());
        var pickedUp = stops.stream()
                .filter(s -> s.orderId().equals(stop.orderId()) && s.kind().equals("pickup"))
                .allMatch(s -> s.state().equals("done"));
        if (!pickedUp) {
            throw new Conflict("not_picked_up", DeliveryRules.NOT_PICKED_UP);
        }
        switch (proof) {
            case "pin" -> {
                if (pin == null || pin.isBlank()) {
                    throw RuleViolation.of("pin", "required", DeliveryRules.PIN_REQUIRED);
                }
                if (!java.security.MessageDigest.isEqual(
                        pin.strip().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        delivery.pin().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw RuleViolation.of("pin", "match", DeliveryRules.PIN_WRONG);
                }
            }
            default -> {
                if (!proof.equals(stop.proofKind()) || stop.proofKey() == null) {
                    throw new Conflict("proof_missing", DeliveryRules.PROOF_MISSING);
                }
            }
        }
        // age-restricted items: the courier confirms the photo ID, the name and the age before handing over
        deliveries.idCheck(stop.orderId()).ifPresent(check -> {
            var answer = idCheck == null ? new IdCheckAnswer(false, false, false) : idCheck;
            handoffs.record(new HandoffChecks.Check(
                    stop.orderId(),
                    delivery.orderType(),
                    null,
                    check.province(),
                    check.age(),
                    userId,
                    "courier",
                    "door",
                    answer.idChecked(),
                    answer.recipientMatches(),
                    answer.ofAge(),
                    null,
                    at));
        });
        runs.droppedOff(stopId, proof, at);
        start(mine.run(), at);
        deliveries.moveState(stop.orderId(), "delivered", at);
        events.publishEvent(new DeliveryCompleted(
                Ids.next(),
                at,
                stop.orderId(),
                mine.run().id(),
                stopId,
                mine.courier().id(),
                proof));
        var all = runs.stops(mine.run().id());
        if (all.stream().allMatch(s -> s.state().equals("done") || s.id().equals(stopId))) {
            runs.moveState(mine.run().id(), "done", at);
            var onShift = couriers.onShift(mine.courier().id()).isPresent();
            couriers.status(mine.courier().id(), onShift ? "available" : "offline");
        }
        return reload(mine.run());
    }

    @Override
    public RunView refuse(String userId, String stopId, String reason) {
        var at = clock.instant();
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.kind().equals("dropoff")) {
            throw new Conflict("not_a_dropoff", DeliveryRules.NOT_YOUR_RUN);
        }
        if (stop.state().equals("done")) {
            return reload(mine.run());
        }
        var check = deliveries
                .idCheck(stop.orderId())
                .orElseThrow(() -> new Conflict("no_id_check", DeliveryRules.NO_ID_CHECK));
        var delivery = delivery(stop.orderId());
        var pickedUp = runs.stops(mine.run().id()).stream()
                .filter(s -> s.orderId().equals(stop.orderId()) && s.kind().equals("pickup"))
                .allMatch(s -> s.state().equals("done"));
        if (!pickedUp) {
            throw new Conflict("not_picked_up", DeliveryRules.NOT_PICKED_UP);
        }
        var checkId = handoffs.record(new HandoffChecks.Check(
                stop.orderId(),
                delivery.orderType(),
                null,
                check.province(),
                check.age(),
                userId,
                "courier",
                "door",
                false,
                false,
                false,
                reason,
                at));
        runs.droppedOff(stopId, "id_refused", at);
        deliveries.moveState(stop.orderId(), "returning", at);
        var shop = delivery.pickups().isEmpty()
                ? null
                : delivery.pickups().getFirst().merchantId();
        if (shop != null) {
            runs.addReturnStop(mine.run().id(), stop.orderId(), shop, at);
        }
        events.publishEvent(new DeliveryRefused(
                Ids.next(), at, stop.orderId(), mine.run().id(), mine.courier().id(), reason, checkId));
        return reload(mine.run());
    }

    @Override
    public RunView returned(String userId, String stopId) {
        var at = clock.instant();
        var mine = ownStop(userId, stopId);
        var stop = mine.stop();
        if (!stop.kind().equals("return")) {
            throw new Conflict("not_a_return", DeliveryRules.NOT_YOUR_RUN);
        }
        if (stop.state().equals("done")) {
            return reload(mine.run());
        }
        runs.droppedOff(stopId, "id_refused", at);
        deliveries.moveState(stop.orderId(), "returned", at);
        var all = runs.stops(mine.run().id());
        if (all.stream().allMatch(s -> s.state().equals("done") || s.id().equals(stopId))) {
            runs.moveState(mine.run().id(), "done", at);
            var onShift = couriers.onShift(mine.courier().id()).isPresent();
            couriers.status(mine.courier().id(), onShift ? "available" : "offline");
        }
        return reload(mine.run());
    }

    @Override
    public Ping ping(String userId, double lat, double lng, @Nullable Double heading) {
        var c = courier(userId);
        if (couriers.onShift(c.id()).isEmpty()) {
            throw new Conflict("not_on_shift", DeliveryRules.NOT_ON_SHIFT);
        }
        var interval = props.pingInterval();
        if (!live.allow(c.id(), interval)) {
            throw new TooManyPings(interval);
        }
        var now = clock.instant();
        live.put(c.id(), new CourierLocations.Position(lat, lng, heading, now), props.positionTtl());
        runs.openRunOf(c.id())
                .ifPresent(run -> deliveries.onRun(run.id()).stream()
                        .filter(d -> d.state().equals("picked_up"))
                        .forEach(d -> live.moved(d.orderId())));
        return new Ping(now, interval.toMillis());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private record Mine(Courier courier, Run run, Stop stop) {}

    private Courier courier(String userId) {
        return couriers.byUser(userId).filter(Courier::active).orElseThrow(NotACourier::new);
    }

    private Shift ownShift(Courier c, String shiftId) {
        return couriers.shift(shiftId)
                .filter(s -> s.courierId().equals(c.id()))
                .orElseThrow(() -> new NotFound("shift", shiftId));
    }

    /** The stop, on the caller's own open run, with the run locked. Others' stops are 404 (ids can't be probed). */
    private Mine ownStop(String userId, String stopId) {
        var c = courier(userId);
        var stop = runs.stop(stopId).orElseThrow(() -> new NotFound("stop", stopId));
        // S-87: the app replays an action whose answer it lost (offline queue). A done stop of the courier's own run
        // stays reachable after the run is done, so the replay is a no-op that answers the run instead of a 404.
        var run = runs.lock(stop.runId())
                .filter(r -> c.id().equals(r.courierId())
                        && (!r.state().equals("done") || stop.state().equals("done")))
                .orElseThrow(() -> new NotFound("stop", stopId));
        // re-read under the lock: a concurrent action may have moved it
        return new Mine(c, run, runs.stop(stopId).orElseThrow());
    }

    private Delivery delivery(String orderId) {
        return deliveries.find(orderId).orElseThrow(() -> new NotFound("delivery", orderId));
    }

    private void start(Run run, Instant at) {
        if (run.state().equals("planned")) {
            runs.moveState(run.id(), "loading", at);
        }
    }

    private RunView reload(Run run) {
        var fresh = runs.find(run.id()).orElseThrow();
        return views.courierView(fresh, runs.stops(fresh.id()));
    }

    static ShiftView view(Shift s) {
        return new ShiftView(s.id(), s.startsAt(), s.endsAt(), s.state(), s.startedAt(), s.endedAt());
    }
}
