package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryProofPhotos;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.ReturnPolicies;
import ca.northline.orders.domain.AfterSale;
import ca.northline.orders.domain.OrderDelivery;
import ca.northline.payments.api.EscrowKind;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TrackOrder}. The timeline follows the order's state (the shops' "Mark packed" moves it to packing / ready;
 * the courier's pickup and drop-off and the customer's confirmation move it on: S-78).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class OrderTrackingService implements TrackOrder {

    static final List<String> FLOW =
            List.of("placed", "accepted", "packing", "ready", "picked_up", "delivered", "confirmed");
    static final Set<String> ENDED = Set.of("cancelled", "refunded", "returned");

    private final TrackingStore orders;
    private final DeliveryRuns runs;
    private final BusinessNames names;
    private final Clock clock;
    private final CourierProgressReader couriers;
    private final DeliveryProofPhotos photos;
    private final AfterSaleStore afterSale;
    private final ReturnPolicies policies;
    private final AfterSaleProperties rules;
    private final ReturnService returns;

    @Override
    public ProofPhoto proofPhoto(String customerId, String orderId) {
        if (orders.order(customerId, orderId).isEmpty()) {
            if (orders.exists(orderId)) {
                throw new AccessDeniedException(NOT_YOUR_ORDER);
            }
            throw new NotFound("order", orderId);
        }
        return photos.photo(orderId)
                .map(p -> new ProofPhoto(p.url().toString(), p.expiresAt()))
                .orElseThrow(() -> new NotFound("proof photo", orderId));
    }

    @Override
    public OrderTracking view(String customerId, String orderId) {
        var o = orders.order(customerId, orderId).orElseThrow(() -> new NotFound("order", orderId));
        var lines = orders.lines(orderId);
        var perShop = new LinkedHashMap<String, List<TrackingStore.LineState>>();
        lines.forEach(l ->
                perShop.computeIfAbsent(l.merchantId(), _ -> new ArrayList<>()).add(l));
        var shops = perShop.entrySet().stream()
                .map(e -> new ShopProgress(
                        e.getKey(),
                        names.displayName(e.getKey()).orElse(""),
                        e.getValue().stream()
                                .mapToInt(TrackingStore.LineState::qty)
                                .sum(),
                        e.getValue().stream().noneMatch(l -> "pending".equals(l.state())),
                        e.getValue().stream().allMatch(l -> "cancelled".equals(l.state()))))
                .toList();
        return new OrderTracking(
                o.id(),
                o.ref(),
                o.type(),
                o.state(),
                o.placedAt(),
                o.subtotalCents(),
                o.deliveryFeeCents(),
                o.taxCents(),
                o.subtotalCents() + o.deliveryFeeCents() + o.taxCents() + o.tipCents() - o.offCents(),
                delivery(o),
                shops,
                steps(o.state()),
                o.deliveredAt(),
                o.deliveryProof(),
                o.confirmedAt(),
                "goods".equals(o.type()) && OrderDelivery.confirmable(o.state()),
                "goods".equals(o.type()) && o.deliveredAt() != null && o.confirmedAt() == null
                        ? EscrowKind.GOODS.releaseAt(o.deliveredAt())
                        : null,
                couriers.of(o.id()),
                canCancel(o),
                afterSale.cancellations(o.id()).stream()
                        .map(c -> new CancellationInfo(
                                c.actorRole(),
                                c.reason(),
                                c.at(),
                                c.whole(),
                                c.merchantId() == null
                                        ? null
                                        : names.displayName(c.merchantId()).orElse(null)))
                        .toList(),
                afterSale
                        .failure(o.id())
                        .filter(f -> o.state().equals("delivery_failed") || f.choice() != null)
                        .map(f -> new FailureInfo(
                                f.reason(),
                                f.failedAt(),
                                f.decideBy(),
                                f.attempts(),
                                f.choice(),
                                f.chosenAt(),
                                f.feeCents()))
                        .orElse(null),
                returnable(o),
                "goods".equals(o.type()) ? returns.ofCustomer(customerId, o.id()) : List.of());
    }

    /** S-151: the customer's own rule (before any shop packed, by default). */
    private boolean canCancel(TrackingStore.Header o) {
        if (!"goods".equals(o.type())) {
            return false;
        }
        var open = afterSale.lines(o.id()).stream()
                .filter(AfterSaleStore.LineFacts::open)
                .toList();
        return !open.isEmpty()
                && rules.customerCancelUntil()
                        .allows(
                                o.state(),
                                open.stream().anyMatch(l -> !l.state().equals("pending")),
                                o.state().equals("picked_up"));
    }

    /** S-151: delivered lines still in their return window and in no return. */
    private List<ReturnableLine> returnable(TrackingStore.Header o) {
        var delivered = o.deliveredAt();
        if (!"goods".equals(o.type())
                || delivered == null
                || !Set.of("delivered", "confirmed").contains(o.state())) {
            return List.of();
        }
        var lines = afterSale.lines(o.id()).stream()
                .filter(l -> l.open() && l.returnId() == null)
                .toList();
        var days = policies.days(lines.stream()
                .map(AfterSaleStore.LineFacts::offerId)
                .filter(java.util.Objects::nonNull)
                .toList());
        var now = clock.instant();
        var out = new ArrayList<ReturnableLine>();
        for (var l : lines) {
            var window = l.offerId() == null ? rules.returnDays() : days.getOrDefault(l.offerId(), rules.returnDays());
            if (AfterSale.returnable(delivered, window, now)) {
                out.add(new ReturnableLine(
                        l.id(),
                        l.merchantId(),
                        l.title(),
                        l.qty(),
                        l.amountCents(),
                        delivered.plus(java.time.Duration.ofDays(window))));
            }
        }
        return out;
    }

    private Delivery delivery(TrackingStore.Header o) {
        var windowId = o.windowId();
        if (windowId != null) {
            var run = runs.run(windowId);
            if (run.isPresent()) {
                var r = run.get();
                var zone = runs.zone(r.market());
                var days = ChronoUnit.DAYS.between(
                        LocalDate.now(clock.withZone(zone)), LocalDate.ofInstant(r.startsAt(), zone));
                var day = days <= 0 ? "today" : days == 1 ? "tomorrow" : "later";
                return new Delivery("pooled", r.label(), day, r.startsAt(), r.endsAt(), r.households(), null);
            }
        }
        var kind =
                "pickup".equals(o.fulfilmentMode()) ? "pickup" : o.deliveryKind() == null ? "direct" : o.deliveryKind();
        return new Delivery(kind, null, null, null, null, 0, o.scheduledFor());
    }

    /** paid → packing → pickup → delivered from the order's state. */
    static List<Step> steps(String state) {
        if (ENDED.contains(state)) {
            return List.of(new Step("paid", "done"));
        }
        if (state.equals("delivery_failed")) {
            // S-151: picked up, not handed over — the delivery step is where it stopped
            return List.of(
                    new Step("paid", "done"),
                    new Step("packing", "done"),
                    new Step("pickup", "done"),
                    new Step("delivered", "current"));
        }
        var at = Math.max(0, FLOW.indexOf(state));
        // index of the current step: placed/accepted/packing → packing (1), ready → pickup (2), picked_up → delivered
        // (3)
        var current = at <= 2 ? 1 : at == 3 ? 2 : at == 4 ? 3 : 4;
        var keys = List.of("paid", "packing", "pickup", "delivered");
        var out = new ArrayList<Step>();
        for (var i = 0; i < keys.size(); i++) {
            out.add(new Step(keys.get(i), i < current ? "done" : i == current ? "current" : "todo"));
        }
        return out;
    }
}
