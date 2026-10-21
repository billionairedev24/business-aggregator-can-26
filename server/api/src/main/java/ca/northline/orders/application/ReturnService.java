package ca.northline.orders.application;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import ca.northline.fulfilment.api.ReturnPickups;
import ca.northline.identity.api.DeliveryAddresses;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.ReturnPolicies;
import ca.northline.orders.api.ReturnUpdated;
import ca.northline.orders.api.SellableOffers;
import ca.northline.orders.application.AfterSaleStore.LineFacts;
import ca.northline.orders.application.AfterSaleStore.OrderFacts;
import ca.northline.orders.application.AfterSaleUseCases.Photo;
import ca.northline.orders.application.AfterSaleUseCases.ReturnLine;
import ca.northline.orders.application.AfterSaleUseCases.ReturnRequest;
import ca.northline.orders.application.AfterSaleUseCases.ReturnView;
import ca.northline.orders.application.AfterSaleUseCases.Returns;
import ca.northline.orders.domain.AfterSale;
import ca.northline.orders.domain.OrderReturn;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.CustomerEscrows;
import ca.northline.payments.api.OrderRefunds;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Returns} (S-151). A customer returns delivered lines of one shop within the window of their category (the
 * leaf's, else its group's, else {@code return-days}: {@link ReturnPolicies}), with a reason and a photo; the lines'
 * money waits meanwhile (their escrow goes on hold, so the 7-day release doesn't pay the shop). The shop approves —
 * collected by a courier on a run ({@link ReturnPickups}) or dropped off by the customer — or declines, which the
 * customer can take to a Northline agent (a dispute per line: the disputes queue). Once the shop has it back the
 * lines are refunded at once ({@link OrderRefunds#refundNow}: the card, the tax reversed, taken back from the shop if
 * it had been paid) and, where the shop chose, restocked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ReturnService implements Returns {

    private static final int RECENT = 200;
    private static final Set<String> DELIVERED = Set.of("delivered", "confirmed");

    private final AfterSaleStore store;
    private final ReturnPhotos photos;
    private final ReturnPolicies policies;
    private final OrderRefunds refunds;
    private final CustomerEscrows escrows;
    private final CustomerCases cases;
    private final SellableOffers offers;
    private final ReturnPickups pickups;
    private final DeliveryAddresses addresses;
    private final DeliveryRuns runs;
    private final BusinessNames names;
    private final AfterSaleProperties props;
    private final ApplicationEventPublisher events;
    private final TrackingBus bus;
    private final Clock clock;

    // ── customer ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public ReturnView request(String customerId, String orderId, ReturnRequest request) {
        if (!AfterSale.RETURN_REASONS.contains(request.reason())) {
            throw RuleViolation.of("reason", "required", AfterSale.RETURN_REASON);
        }
        var note = request.note() == null || request.note().isBlank()
                ? null
                : request.note().strip();
        if (note != null && note.length() > AfterSale.MAX_RETURN_NOTE) {
            throw RuleViolation.of("note", "length", AfterSale.RETURN_NOTE_LENGTH);
        }
        if (request.lineIds().isEmpty()) {
            throw RuleViolation.of("lineIds", "required", AfterSale.RETURN_LINES);
        }
        var photo = request.photo();
        if (photo == null || photo.isEmpty()) {
            throw RuleViolation.of("photo", "required", AfterSale.RETURN_PHOTO);
        }
        var bytes = photo.toArray();
        var type = AfterSale.imageType(bytes);
        if (type == null || bytes.length > AfterSale.MAX_PHOTO_BYTES) {
            throw RuleViolation.of("photo", "format", AfterSale.RETURN_PHOTO_FILE);
        }
        var order = store.lock(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new NotFound("order", orderId));
        if (!order.type().equals("goods") || !DELIVERED.contains(order.state())) {
            throw new Conflict("not_delivered", AfterSale.NOT_DELIVERED);
        }
        var all = store.lines(orderId);
        var chosen =
                all.stream().filter(l -> request.lineIds().contains(l.id())).toList();
        if (chosen.size() != Set.copyOf(request.lineIds()).size()) {
            throw RuleViolation.of("lineIds", "required", AfterSale.RETURN_LINES);
        }
        if (chosen.stream().map(LineFacts::merchantId).distinct().count() > 1) {
            throw RuleViolation.of("lineIds", "one_shop", AfterSale.ONE_SHOP);
        }
        var now = clock.instant();
        var windows = policies.days(
                chosen.stream().map(LineFacts::offerId).filter(Objects::nonNull).toList());
        for (var line : chosen) {
            if (!line.open() || line.returnId() != null) {
                throw new Conflict("already_returning", AfterSale.ALREADY_RETURNING);
            }
            var days = line.offerId() == null
                    ? props.returnDays()
                    : windows.getOrDefault(line.offerId(), props.returnDays());
            if (days == 0) {
                throw new Conflict("not_returnable", AfterSale.NOT_RETURNABLE);
            }
            if (!AfterSale.returnable(order.deliveredAt(), days, now)) {
                throw new Conflict("window_closed", AfterSale.WINDOW_CLOSED);
            }
        }
        var id = Ids.next();
        var key = orderId + "/" + id;
        photos.put(key, photo, type);
        var ids = chosen.stream().map(LineFacts::id).sorted().toList();
        var r = new OrderReturn(
                id,
                store.nextReturnRef(),
                orderId,
                chosen.getFirst().merchantId(),
                customerId,
                request.reason(),
                note,
                key,
                type,
                chosen.stream().mapToLong(LineFacts::amountCents).sum(),
                "requested",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                ids,
                now);
        store.insert(r);
        store.lineReturn(ids, id);
        ids.forEach(l -> refunds.hold(GoodsEscrowRelease.LINE, l));
        publish(r, now);
        return view(r, order, all);
    }

    @Override
    public ReturnView withdraw(String customerId, String returnId) {
        var r = own(customerId, returnId);
        var now = clock.instant();
        var next = r.withdrawn(now);
        store.update(next);
        store.lineReturn(next.lineIds(), null);
        next.lineIds().forEach(l -> refunds.resume(GoodsEscrowRelease.LINE, l));
        if ("pickup".equals(r.method())) {
            pickups.cancel(returnId);
        }
        publish(next, now);
        return view(next);
    }

    @Override
    public ReturnView escalate(String customerId, String returnId, String statement) {
        if (statement.isBlank()) {
            throw RuleViolation.of("statement", "required", AfterSale.ESCALATE_STATEMENT);
        }
        if (statement.strip().length() > AfterSale.MAX_RETURN_NOTE) {
            throw RuleViolation.of("statement", "length", AfterSale.RETURN_NOTE_LENGTH);
        }
        var r = own(customerId, returnId);
        if (!r.state().equals("declined")) {
            throw new Conflict("not_declined", AfterSale.NOT_DECLINED);
        }
        var disputes = new ArrayList<String>();
        for (var e : escrows.of(customerId, GoodsEscrowRelease.LINE, r.lineIds())) {
            if (!e.openCase() && !e.state().equals("refunded")) {
                disputes.add(cases.openDispute(
                        e.escrowId(), customerId, "Return %s declined".formatted(r.ref()), statement.strip()));
            }
        }
        var next = r.escalated(disputes);
        store.update(next);
        publish(next, clock.instant());
        return view(next);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnView> ofCustomer(String customerId, String orderId) {
        var order = store.find(orderId)
                .filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new NotFound("order", orderId));
        var lines = store.lines(orderId);
        return store.returnsOfOrder(orderId).stream()
                .map(r -> view(r, order, lines))
                .toList();
    }

    /** Every return of the order (the console). */
    @Transactional(readOnly = true)
    List<ReturnView> ofOrder(String orderId) {
        var order = store.find(orderId).orElse(null);
        var lines = store.lines(orderId);
        return store.returnsOfOrder(orderId).stream()
                .map(r -> view(r, order, lines))
                .toList();
    }

    // ── shop ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ReturnView> ofMerchant(String merchantId) {
        return store.returnsOfMerchant(merchantId, RECENT).stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnView ofMerchant(String merchantId, String returnId) {
        return view(shop(merchantId, returnId, false));
    }

    @Override
    public ReturnView approve(String merchantId, String returnId, String actorId, String method, boolean restock) {
        var r = shop(merchantId, returnId, true);
        var now = clock.instant();
        var next = r.approve(method, restock, actorId, now);
        store.update(next);
        if (method.equals("pickup")) {
            requestPickup(next);
        }
        publish(next, now);
        return view(next);
    }

    @Override
    public ReturnView decline(String merchantId, String returnId, String actorId, String reason) {
        var r = shop(merchantId, returnId, true);
        var now = clock.instant();
        var next = r.decline(reason, actorId, now);
        store.update(next);
        next.lineIds().forEach(l -> refunds.resume(GoodsEscrowRelease.LINE, l));
        publish(next, now);
        return view(next);
    }

    @Override
    public ReturnView received(String merchantId, String returnId, String actorId) {
        var r = shop(merchantId, returnId, true);
        var now = clock.instant();
        var next = settle(r.received(now), now);
        if ("pickup".equals(r.method())) {
            pickups.cancel(returnId); // the shop has it already: the courier needn't come
        }
        return view(next);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Photo> photo(String merchantId, String returnId) {
        var r = shop(merchantId, returnId, false);
        var key = r.photoKey();
        return key == null ? Optional.empty() : photos.get(key).map(p -> new Photo(p.bytes(), p.contentType()));
    }

    // ── fulfilment's events ───────────────────────────────────────────────────────────────────────────────────────

    /** The courier collected it at the door. A replay changes nothing. */
    void collected(String returnId, Instant at) {
        var r = store.lockReturn(returnId).orElse(null);
        if (r == null || !r.state().equals("approved")) {
            return;
        }
        var next = r.collected(at);
        store.update(next);
        publish(next, at);
    }

    /** The courier brought it back to the shop: refunded at once. A replay changes nothing. */
    void delivered(String returnId, Instant at) {
        var r = store.lockReturn(returnId).orElse(null);
        if (r == null || !(r.state().equals("approved") || r.state().equals("collected"))) {
            return;
        }
        settle(r.received(at), at);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Received: each line refunded now, restocked where the shop chose; the order refunded when nothing is left. */
    private OrderReturn settle(OrderReturn received, Instant at) {
        store.update(received);
        var order = store.lock(received.orderId()).orElseThrow();
        var lines = store.lines(received.orderId()).stream()
                .filter(l -> received.lineIds().contains(l.id()))
                .toList();
        var what = "Return %s received by the shop".formatted(received.ref());
        for (var line : lines) {
            refunds.refundNow(GoodsEscrowRelease.LINE, line.id(), "returned", what);
        }
        if (received.restock()) {
            offers.restock(
                    lines.stream()
                            .filter(l -> l.offerId() != null)
                            .map(l -> new SellableOffers.Take(
                                    Objects.requireNonNull(l.offerId()), l.variantId(), l.qty()))
                            .toList(),
                    "return",
                    received.id());
        }
        store.refundLines(received.lineIds(), received.restock());
        if (store.lines(order.id()).stream().noneMatch(LineFacts::open)) {
            store.state(order.id(), "refunded");
        }
        var refunded = received.refunded(at);
        store.update(refunded);
        publish(refunded, at);
        bus.changed(order.id());
        log.info("Return {} received and refunded ({} cents)", refunded.ref(), refunded.amountCents());
        return refunded;
    }

    private void requestPickup(OrderReturn r) {
        var order = store.find(r.orderId()).orElseThrow();
        var address = order.addressId() == null
                ? null
                : addresses.find(r.customerId(), order.addressId()).orElse(null);
        var area = Objects.requireNonNullElse(order.deliveryArea(), "");
        pickups.request(new ReturnPickups.Request(
                r.id(),
                r.ref(),
                r.orderId(),
                r.merchantId(),
                runs.market(area).orElse(area),
                r.customerId(),
                address == null
                        ? new Dropoff("", null, area, null, null, null, null)
                        : new Dropoff(
                                address.street(),
                                address.unit(),
                                address.city(),
                                address.postal(),
                                address.note(),
                                null,
                                null)));
    }

    private OrderReturn own(String customerId, String returnId) {
        return store.lockReturn(returnId)
                .filter(r -> r.customerId().equals(customerId))
                .orElseThrow(() -> new NotFound("return", returnId));
    }

    private OrderReturn shop(String merchantId, String returnId, boolean lock) {
        return (lock ? store.lockReturn(returnId) : store.returnById(returnId))
                .filter(r -> r.merchantId().equals(merchantId))
                .orElseThrow(() -> new NotFound("return", returnId));
    }

    private void publish(OrderReturn r, Instant at) {
        events.publishEvent(new ReturnUpdated(
                Ids.next(), at, r.id(), r.ref(), r.orderId(), r.merchantId(), r.state(), r.method(), r.amountCents()));
    }

    private ReturnView view(OrderReturn r) {
        var order = store.find(r.orderId()).orElse(null);
        return view(r, order, store.lines(r.orderId()));
    }

    private ReturnView view(OrderReturn r, @Nullable OrderFacts order, List<LineFacts> lines) {
        var mine = lines.stream()
                .filter(l -> r.lineIds().contains(l.id()))
                .map(l -> new ReturnLine(l.id(), l.title(), l.qty(), l.amountCents()))
                .toList();
        return new ReturnView(
                r.id(),
                r.ref(),
                r.orderId(),
                order == null ? null : order.ref(),
                r.merchantId(),
                names.displayName(r.merchantId()).orElse(null),
                r.state(),
                r.reason(),
                r.note(),
                r.photoKey() != null,
                r.method(),
                r.restock(),
                r.declineReason(),
                r.amountCents(),
                mine,
                r.createdAt(),
                r.decidedAt(),
                r.collectedAt(),
                r.receivedAt(),
                r.refundedAt(),
                r.state().equals("requested") || r.state().equals("approved"),
                r.state().equals("declined"));
    }
}
