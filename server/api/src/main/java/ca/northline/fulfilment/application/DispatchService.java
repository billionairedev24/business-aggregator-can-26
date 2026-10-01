package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryAssigned;
import ca.northline.fulfilment.api.RunPlanned;
import ca.northline.fulfilment.application.DeliveryStore.Delivery;
import ca.northline.fulfilment.application.DispatchUseCases.AssignCouriers;
import ca.northline.fulfilment.application.DispatchUseCases.PlanRuns;
import ca.northline.fulfilment.application.RunStore.Run;
import ca.northline.fulfilment.application.RunStore.Stop;
import ca.northline.fulfilment.domain.RoutePlanner;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run planning and courier assignment (S-86).
 *
 * <ul>
 *   <li><b>Pooled:</b> once a window's customers' cut-off ({@code orderBy}) has passed, its orders become runs of at
 *       most {@code maxDropsPerRun} drop-offs ("tonight's run created from orders"). Orders whose shops haven't
 *       packed yet join too: shops pack by {@code packBy}, and the courier's pickup checks it.
 *   <li><b>Direct goods:</b> one run per order once every shop has packed. <b>Food:</b> one run per order once the
 *       kitchen accepted it (ready-by known), the pickup due at ready-by.
 *   <li><b>Assignment:</b> runs starting within {@code assignLead} (direct: at once) get the courier of the market who
 *       is on shift, available and longest without a run. The run row is locked ({@code skip locked}) and the courier
 *       claimed with a conditional update, so concurrent planners never give one run two couriers or one courier two
 *       runs (a unique index on open runs per courier backs it).
 * </ul>
 */
@Slf4j
@Service
@Transactional
class DispatchService implements PlanRuns, AssignCouriers {

    private final DeliveryStore deliveries;
    private final RunStore runs;
    private final CourierStore couriers;
    private final PublicDirectory directory;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final FulfilmentProperties props;
    private final RoutePlanner planner;

    DispatchService(
            DeliveryStore deliveries,
            RunStore runs,
            CourierStore couriers,
            PublicDirectory directory,
            ApplicationEventPublisher events,
            Clock clock,
            FulfilmentProperties props) {
        this.deliveries = deliveries;
        this.runs = runs;
        this.couriers = couriers;
        this.directory = directory;
        this.events = events;
        this.clock = clock;
        this.props = props;
        this.planner = new RoutePlanner(new RoutePlanner.Settings(
                props.minutesPerKm(), props.minLeg(), props.unknownLeg(), props.pickupDwell(), props.dropoffDwell()));
    }

    @Override
    public int plan(@Nullable String market) {
        var now = clock.instant();
        runs.lockPlanning();
        var shops = new HashMap<String, RoutePlanner.Pickup>();
        int planned = 0;
        var pooled = new LinkedHashMap<String, List<Delivery>>();
        for (var d : deliveries.waitingPooled(now)) {
            if (market == null || market.equalsIgnoreCase(d.market())) {
                pooled.computeIfAbsent(Objects.requireNonNull(d.windowId()), _ -> new ArrayList<>())
                        .add(d);
            }
        }
        for (var window : pooled.values()) {
            planned += planWindow(window, shops, now);
        }
        for (var d : deliveries.waitingDirect()) {
            if (market != null && !market.equalsIgnoreCase(d.market())) {
                continue;
            }
            var food = d.orderType().equals("food");
            if (food ? d.readyBy() == null : !d.allPacked()) {
                continue; // food: not accepted yet; goods: a shop is still packing
            }
            var start = food && Objects.requireNonNull(d.readyBy()).isAfter(now) ? d.readyBy() : now;
            createRun(List.of(d), "direct", null, null, 1, start, null, null, shops, now);
            planned++;
        }
        return planned;
    }

    /** One or more runs for a window: the full route is split into parts of at most {@code maxDropsPerRun} drops. */
    private int planWindow(List<Delivery> window, Map<String, RoutePlanner.Pickup> shops, Instant now) {
        var first = window.getFirst();
        var start = Objects.requireNonNull(first.startsAt());
        var route = planner.plan(pickups(window, shops), dropoffs(window), start);
        var byOrder = new HashMap<String, Delivery>();
        window.forEach(d -> byOrder.put(d.orderId(), d));
        var inRouteOrder = route.stream()
                .filter(s -> s.kind().equals("dropoff"))
                .map(s -> byOrder.get(s.orderId()))
                .toList();
        var windowId = Objects.requireNonNull(first.windowId());
        var part = runs.nextPart(windowId);
        int created = 0;
        for (int i = 0; i < inRouteOrder.size(); i += props.maxDropsPerRun()) {
            var chunk = inRouteOrder.subList(i, Math.min(inRouteOrder.size(), i + props.maxDropsPerRun()));
            createRun(
                    chunk,
                    "pooled",
                    windowId,
                    first.windowLabel(),
                    part++,
                    start,
                    first.endsAt(),
                    first.packBy(),
                    shops,
                    now);
            created++;
        }
        return created;
    }

    private void createRun(
            List<Delivery> orders,
            String kind,
            @Nullable String windowId,
            @Nullable String label,
            int part,
            Instant start,
            @Nullable Instant endsAt,
            @Nullable Instant packBy,
            Map<String, RoutePlanner.Pickup> shops,
            Instant now) {
        var runId = Ids.next();
        var route = planner.plan(pickups(orders, shops), dropoffs(orders), start);
        var stops = new ArrayList<Stop>();
        for (int i = 0; i < route.size(); i++) {
            var s = route.get(i);
            stops.add(new Stop(
                    Ids.next(),
                    runId,
                    s.orderId(),
                    s.kind(),
                    i + 1,
                    s.merchantId(),
                    s.eta(),
                    "pending",
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        var market = orders.getFirst().market();
        runs.insert(
                new Run(
                        runId,
                        market,
                        kind,
                        "planned",
                        windowId,
                        label,
                        part,
                        start,
                        endsAt == null ? route.getLast().eta() : endsAt,
                        packBy,
                        null,
                        now,
                        null,
                        null,
                        null,
                        RoutePlanner.HEURISTIC),
                stops);
        var orderIds = orders.stream().map(Delivery::orderId).toList();
        deliveries.attach(orderIds, runId, now);
        events.publishEvent(new RunPlanned(
                Ids.next(), now, runId, market, kind, windowId, orderIds, stops.size(), RoutePlanner.HEURISTIC));
        log.info("Planned {} run {} in {}: {} orders, {} stops", kind, runId, market, orderIds.size(), stops.size());
    }

    @Override
    public int assign(@Nullable String market) {
        var now = clock.instant();
        int assigned = 0;
        for (var run : runs.awaitingCourier(now.plus(props.assignLead()))) {
            if (market != null && !market.equalsIgnoreCase(run.market())) {
                continue;
            }
            if (assign(run.id(), now)) {
                assigned++;
            }
        }
        return assigned;
    }

    /** The first available courier for the run, if any; false when the run is taken or nobody is free. */
    boolean assign(String runId, Instant now) {
        var run = runs.lockUnassigned(runId).orElse(null);
        if (run == null) {
            return false;
        }
        var at = run.startsAt() != null && run.startsAt().isAfter(now) ? run.startsAt() : now;
        for (var courier : couriers.available(run.market(), at)) {
            if (couriers.claim(courier.id(), now)) {
                give(run, courier.id(), now);
                return true;
            }
        }
        return false;
    }

    /** The run goes to the courier (already claimed); {@code delivery.assigned}. */
    void give(Run run, String courierId, Instant now) {
        runs.assign(run.id(), courierId, now);
        var onRun = deliveries.onRun(run.id());
        var orderIds = onRun.stream().map(Delivery::orderId).toList();
        var merchantIds = onRun.stream()
                .flatMap(d -> d.pickups().stream())
                .map(DeliveryStore.Pickup::merchantId)
                .distinct()
                .sorted()
                .toList();
        var orderType = onRun.stream().anyMatch(d -> d.orderType().equals("food")) ? "food" : "goods";
        events.publishEvent(
                new DeliveryAssigned(Ids.next(), now, run.id(), courierId, orderIds, merchantIds, orderType));
        log.info("Run {} assigned to courier {}", run.id(), courierId);
    }

    private List<RoutePlanner.Pickup> pickups(List<Delivery> orders, Map<String, RoutePlanner.Pickup> shops) {
        var perShop = new LinkedHashMap<String, List<String>>();
        for (var d : orders) {
            for (var p : d.pickups()) {
                perShop.computeIfAbsent(p.merchantId(), _ -> new ArrayList<>()).add(d.orderId());
            }
        }
        var out = new ArrayList<RoutePlanner.Pickup>();
        perShop.forEach((merchantId, orderIds) -> {
            var located = shops.computeIfAbsent(
                    merchantId,
                    id -> directory
                            .byId(id)
                            .map(b -> new RoutePlanner.Pickup(id, List.of(), b.lat(), b.lng()))
                            .orElse(new RoutePlanner.Pickup(id, List.of(), null, null)));
            out.add(new RoutePlanner.Pickup(merchantId, orderIds, located.lat(), located.lng()));
        });
        return out;
    }

    private static List<RoutePlanner.Dropoff> dropoffs(List<Delivery> orders) {
        return orders.stream()
                .map(d -> {
                    var to = d.dropoff();
                    return to == null
                            ? new RoutePlanner.Dropoff(d.orderId(), null, null, null, null)
                            : new RoutePlanner.Dropoff(d.orderId(), to.postal(), to.street(), to.lat(), to.lng());
                })
                .toList();
    }
}
