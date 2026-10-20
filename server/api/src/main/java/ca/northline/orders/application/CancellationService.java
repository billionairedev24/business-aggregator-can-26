package ca.northline.orders.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.fulfilment.api.CourierPickups;
import ca.northline.orders.api.OrderCancelled;
import ca.northline.orders.api.SellableOffers;
import ca.northline.orders.application.AfterSaleStore.Cancellation;
import ca.northline.orders.application.AfterSaleStore.LineFacts;
import ca.northline.orders.application.AfterSaleStore.OrderFacts;
import ca.northline.orders.application.AfterSaleUseCases.CancelOrders;
import ca.northline.orders.application.AfterSaleUseCases.Cancelled;
import ca.northline.orders.domain.AfterSale;
import ca.northline.orders.domain.AfterSale.CancelUntil;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CancelOrders} (S-151). The order row is locked, the rule of the actor checked, the lines cancelled and their
 * stock restored (when the canceller chose to — always for the customer and a failed delivery, whose goods are back
 * at the shops), and {@code order.cancelled} published; the money and the delivery follow it
 * ({@link AfterSaleSettlement}, {@link DispatchHandover}). A shop cancels only its own lines; when nothing else is left
 * on the order, the whole order closes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CancellationService implements CancelOrders {

    private final AfterSaleStore store;
    private final SellableOffers offers;
    private final CourierPickups pickups;
    private final AfterSaleProperties props;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final TrackingBus bus;
    private final Clock clock;

    @Override
    public Cancelled byCustomer(String customerId, String orderId, String reason, @Nullable String note) {
        var order = store.lock(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new NotFound("order", orderId));
        check(order, reason, note, AfterSale.CUSTOMER_REASONS, false);
        var lines = store.lines(orderId).stream().filter(LineFacts::open).toList();
        allowed(props.customerCancelUntil(), order, lines);
        return cancel(order, lines, null, customerId, "customer", reason, note, true, "cancelled");
    }

    @Override
    public Cancelled bySeller(
            String merchantId, String orderId, String actorId, String reason, @Nullable String note, boolean restock) {
        var order = store.lock(orderId).orElseThrow(() -> new NotFound("order", orderId));
        var all = store.lines(orderId);
        if (all.stream().noneMatch(l -> l.merchantId().equals(merchantId))) {
            throw new NotFound("order", orderId); // not this shop's order
        }
        check(order, reason, note, AfterSale.MERCHANT_REASONS, true);
        var own = all.stream()
                .filter(l -> l.merchantId().equals(merchantId) && l.open())
                .toList();
        if (own.isEmpty()) {
            throw new Conflict("nothing_to_cancel", AfterSale.NOTHING_TO_CANCEL);
        }
        var pickedUp = pickups.atMerchant(merchantId, List.of(orderId)).get(orderId);
        var rule = props.sellerCancelUntil();
        if (!rule.allows(
                order.state(),
                own.stream().anyMatch(l -> !l.state().equals("pending")),
                pickedUp != null && pickedUp.pickedUpAt() != null)) {
            throw new Conflict("too_late", rule.refusal());
        }
        var others = all.stream()
                .filter(l -> !l.merchantId().equals(merchantId) && l.open())
                .toList();
        return cancel(
                order,
                own,
                others.isEmpty() ? null : merchantId,
                actorId,
                "merchant",
                reason,
                note,
                restock,
                "cancelled");
    }

    @Override
    public Cancelled byStaff(
            String orderId, String staffId, String roles, String reason, @Nullable String note, boolean restock) {
        var order = store.lock(orderId).orElseThrow(() -> new NotFound("order", orderId));
        check(order, reason, note, AfterSale.STAFF_REASONS, true);
        var lines = store.lines(orderId).stream().filter(LineFacts::open).toList();
        allowed(props.staffCancelUntil(), order, lines);
        var before = order.state();
        var done = cancel(order, lines, null, staffId, "staff", reason, note, restock, "cancelled");
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                roles,
                "orders.order_cancelled",
                "order",
                orderId,
                Map.of("state", before),
                Map.of(
                        "state",
                        done.state(),
                        "reason",
                        reason,
                        "restock",
                        restock,
                        "lines",
                        done.lineIds().size())));
        return done;
    }

    /**
     * A failed delivery is refunded (S-151): the customer chose it, or the decision window passed. The goods are back
     * at the shops, so their units go back on sale. The order becomes {@code refunded}.
     */
    Cancelled refundFailed(OrderFacts order, @Nullable String customerId, Instant at) {
        var lines = store.lines(order.id()).stream().filter(LineFacts::open).toList();
        return cancel(
                order,
                lines,
                null,
                customerId,
                customerId == null ? "system" : "customer",
                AfterSale.DELIVERY_FAILED,
                null,
                true,
                "refunded");
    }

    private Cancelled cancel(
            OrderFacts order,
            List<LineFacts> lines,
            @Nullable String merchantId,
            @Nullable String actorId,
            String role,
            String reason,
            @Nullable String note,
            boolean restock,
            String finalState) {
        var now = clock.instant();
        var whole = merchantId == null;
        var ids = lines.stream().map(LineFacts::id).toList();
        store.cancelLines(ids, restock, now);
        if (restock) {
            offers.giveBack(lines.stream()
                    .filter(l -> l.offerId() != null)
                    .map(l -> new SellableOffers.Take(
                            java.util.Objects.requireNonNull(l.offerId()), l.variantId(), l.qty()))
                    .toList());
        }
        String state;
        if (whole) {
            store.close(order.id(), finalState, now);
            state = finalState;
        } else {
            store.readyIfPacked(order.id());
            state = store.find(order.id()).map(OrderFacts::state).orElse(order.state());
        }
        var text = note == null || note.isBlank() ? null : note.strip();
        var cancellation = store.insertCancellation(new Cancellation(
                Ids.next(), order.id(), merchantId, actorId, role, reason, text, restock, ids, whole, now));
        var merchants =
                lines.stream().map(LineFacts::merchantId).distinct().sorted().toList();
        events.publishEvent(new OrderCancelled(
                Ids.next(), now, order.id(), order.type(), role, reason, whole, merchants, ids, state, cancellation));
        bus.changed(order.id());
        log.info(
                "Order {}: {} line(s) cancelled by {} ({}){}",
                order.id(),
                ids.size(),
                role,
                reason,
                whole ? ", order closed" : "");
        return new Cancelled(cancellation, order.id(), state, whole, ids);
    }

    private static void check(
            OrderFacts order, String reason, @Nullable String note, Set<String> reasons, boolean noteForOther) {
        if (!order.type().equals("goods")) {
            throw new Conflict("not_cancellable", AfterSale.NOT_GOODS);
        }
        if (AfterSale.CLOSED.contains(order.state())) {
            throw new Conflict("order_closed", AfterSale.ALREADY_CLOSED);
        }
        if (!reasons.contains(reason)) {
            throw RuleViolation.of("reason", "required", AfterSale.REASON);
        }
        if (note != null && note.strip().length() > AfterSale.MAX_NOTE) {
            throw RuleViolation.of("note", "length", AfterSale.NOTE_LENGTH);
        }
        if (noteForOther && reason.equals("other") && (note == null || note.isBlank())) {
            throw RuleViolation.of("note", "required", AfterSale.NOTE_REQUIRED);
        }
    }

    private static void allowed(CancelUntil rule, OrderFacts order, List<LineFacts> lines) {
        var packed = lines.stream().anyMatch(l -> !l.state().equals("pending"));
        if (!rule.allows(order.state(), packed, order.state().equals("picked_up"))) {
            throw new Conflict("too_late", rule.refusal());
        }
    }
}
