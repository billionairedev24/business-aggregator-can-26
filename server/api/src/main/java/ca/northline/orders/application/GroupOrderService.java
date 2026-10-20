package ca.northline.orders.application;

import ca.northline.food.api.FoodCheckoutFacts;
import ca.northline.food.api.FoodMenuPricing;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.SecondFactors;
import ca.northline.orders.application.FoodCheckout.Started;
import ca.northline.orders.application.FoodCheckout.Tip;
import ca.northline.orders.application.GroupOrderStore.GroupRow;
import ca.northline.orders.application.GroupOrderStore.ItemRow;
import ca.northline.orders.application.GroupOrderStore.ShareRow;
import ca.northline.orders.application.GroupOrders.GroupCart;
import ca.northline.orders.application.GroupOrders.GroupView;
import ca.northline.orders.application.GroupOrders.HostGroupOrder;
import ca.northline.orders.application.GroupOrders.Item;
import ca.northline.orders.application.GroupOrders.ItemView;
import ca.northline.orders.application.GroupOrders.JoinGroupOrder;
import ca.northline.orders.application.GroupOrders.Member;
import ca.northline.orders.application.GroupOrders.PayGroupOrder;
import ca.northline.orders.application.GroupOrders.Preview;
import ca.northline.orders.application.GroupOrders.Share;
import ca.northline.orders.application.GroupOrders.ShareStarted;
import ca.northline.orders.application.GroupOrders.Start;
import ca.northline.orders.application.GroupOrders.StartGroupOrder;
import ca.northline.orders.application.GroupOrders.ViewGroupOrder;
import ca.northline.orders.domain.CheckoutMessages;
import ca.northline.orders.domain.FoodOrderRules;
import ca.northline.orders.domain.GroupOrderRules;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.OrderRefunds;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.PaymentStepUp;
import ca.northline.payments.api.TaxCalculations;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Group food orders (S-159) on S-57's food checkout. Every change locks the group's row first, so a guest adding a
 * dish and the host locking the cart never interleave. The host pays for everyone through {@link FoodCheckoutService}
 * unchanged; a split bill gives each person a share (see {@link GroupOrderRules#split}) with its own manual-capture
 * card payment and escrow hold ({@code food_order} / the share's id), and the order is entered once every share is
 * authorized ({@link FoodCheckoutService#placeGroup}). Cancelling before the kitchen has it releases every held share
 * through payments' refund queue ({@link OrderRefunds#refundNow}: an uncaptured hold is voided).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class GroupOrderService
        implements StartGroupOrder, JoinGroupOrder, GroupCart, HostGroupOrder, PayGroupOrder, ViewGroupOrder {

    static final String REF_TYPE = FoodCheckoutService.REF_TYPE;
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final GroupOrderStore store;
    private final FoodCheckoutService food;
    private final FoodCheckoutStore checkouts;
    private final FoodCheckoutFacts kitchens;
    private final FoodMenuPricing pricing;
    private final PaymentAuthorizations payments;
    private final EscrowLifecycle escrow;
    private final OrderRefunds refunds;
    private final TaxCalculations taxes;
    private final PersonDirectory people;
    private final SecondFactors secondFactors;
    private final PaymentStepUp stepUp;
    private final PaymentSettings settings;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    // ── start, join, the shared cart ───────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public GroupView start(String hostId, Start s) {
        if (!GroupOrderRules.PAYMENTS.contains(s.payment())) {
            throw RuleViolation.of("payment", "required", GroupOrderRules.PAYMENT);
        }
        var isDelivery = s.mode().equals("delivery");
        if (!isDelivery && !s.mode().equals("pickup")) {
            throw RuleViolation.of("mode", "required", FoodOrderRules.MODE);
        }
        var d = isDelivery ? s.delivery() : null;
        if (isDelivery) {
            if (d == null) {
                throw RuleViolation.of("delivery", "required", FoodOrderRules.ADDRESS_REQUIRED);
            }
            FoodOrderRules.checkDelivery(d.dropoff(), d.extras(), d.note(), d.unit());
        }
        var kitchen = kitchens.kitchen(s.merchantId(), d == null ? null : d.lat(), d == null ? null : d.lng())
                .orElseThrow(() -> new NotFound("kitchen", s.merchantId()));
        if (!kitchen.groupOrders()) {
            throw GroupOrderRules.off(kitchen.name());
        }
        FoodCheckoutService.checkFulfilment(kitchen, isDelivery);
        if (s.scheduledFor() != null && !kitchen.slots().contains(s.scheduledFor())) {
            throw RuleViolation.of("scheduledFor", "slot", FoodOrderRules.SLOT);
        }
        var now = clock.instant();
        for (var attempt = 0; ; attempt++) {
            var row = new GroupRow(
                    Ids.next(),
                    GroupOrderRules.newCode(),
                    hostId,
                    kitchen.merchantId(),
                    kitchen.name(),
                    kitchen.slug(),
                    kitchen.groupMax(),
                    List.of(hostId),
                    "open",
                    s.payment(),
                    s.mode(),
                    s.scheduledFor(),
                    d == null ? null : json.writeValueAsString(d),
                    "none",
                    0,
                    null,
                    null,
                    now,
                    null,
                    null,
                    null);
            if (store.insert(row)) {
                return view(row, hostId);
            }
            if (attempt >= 4) {
                throw new IllegalStateException("no free group order code after 5 draws");
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(String code) {
        var g = byCode(code);
        var host = people.people(List.of(g.hostId())).get(g.hostId());
        return new Preview(
                g.code(),
                g.kitchenName(),
                g.kitchenSlug(),
                host == null ? "" : host.firstName(),
                g.state(),
                g.members().size(),
                g.groupMax());
    }

    @Override
    @Transactional
    public GroupView join(String userId, String code) {
        var g = store.lock(byCode(code).id()).orElseThrow();
        if (g.member(userId)) {
            return view(g, userId);
        }
        GroupOrderRules.requireOpen(g.state());
        if (g.members().size() >= g.groupMax()) {
            throw GroupOrderRules.full(g.groupMax());
        }
        store.addMember(g.id(), userId);
        var members = new ArrayList<>(g.members());
        members.add(userId);
        return view(g.withMembers(members), userId);
    }

    @Override
    @Transactional
    public GroupView add(String userId, String groupId, Item item) {
        var g = member(userId, groupId);
        GroupOrderRules.requireOpen(g.state());
        var mine = store.items(groupId).stream()
                .filter(i -> i.userId().equals(userId))
                .count();
        if (mine >= GroupOrderRules.MAX_ITEMS_PER_PERSON) {
            throw RuleViolation.of("items", "range", GroupOrderRules.TOO_MANY);
        }
        var at = Objects.requireNonNullElse(g.scheduledFor(), clock.instant());
        var priced = item.comboId() != null
                ? pricing.price(new FoodMenuPricing.Request(
                        g.merchantId(),
                        at,
                        List.of(),
                        List.of(new FoodMenuPricing.ComboLine(item.comboId(), item.qty(), item.itemIds()))))
                : pricing.price(new FoodMenuPricing.Request(
                        g.merchantId(),
                        at,
                        List.of(new FoodMenuPricing.ItemLine(
                                Objects.requireNonNullElse(item.itemId(), ""),
                                item.qty(),
                                item.optionIds(),
                                item.note())),
                        List.of()));
        var line = priced.lines().getFirst();
        if (line.ageClass() != null) {
            // one person's ID can't cover a whole group at the door (2026-10-04's rules check the recipient)
            throw new Conflict(
                    "age_restricted", line.title() + " is age-restricted. Order it on its own, not in a group order.");
        }
        store.insertItem(new ItemRow(
                Ids.next(),
                groupId,
                userId,
                line.itemId(),
                line.comboId(),
                line.qty(),
                item.comboId() == null ? item.optionIds() : List.of(),
                item.comboId() == null ? List.of() : item.itemIds(),
                line.note(),
                line.title(),
                line.unitCents(),
                line.totalCents(),
                json.writeValueAsString(line.choices().stream()
                        .map(FoodMenuPricing.Choice::name)
                        .toList()),
                line.ageClass(),
                clock.instant()));
        return view(g, userId);
    }

    @Override
    @Transactional
    public GroupView remove(String userId, String groupId, String itemId) {
        var g = member(userId, groupId);
        GroupOrderRules.requireOpen(g.state());
        var it = store.items(groupId).stream()
                .filter(i -> i.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NotFound("group_order_item", itemId));
        if (!it.userId().equals(userId) && !g.hostId().equals(userId)) {
            throw new GroupForbidden("not_yours", "You can take out only your own dishes.");
        }
        store.deleteItem(groupId, itemId);
        return view(g, userId);
    }

    @Override
    @Transactional
    public GroupView leave(String userId, String groupId) {
        var g = member(userId, groupId);
        if (g.hostId().equals(userId)) {
            throw GroupOrderRules.hostCantLeave();
        }
        GroupOrderRules.requireOpen(g.state());
        store.deleteItemsOf(groupId, userId);
        store.removeMember(groupId, userId);
        return view(
                g.withMembers(
                        g.members().stream().filter(m -> !m.equals(userId)).toList()),
                g.hostId());
    }

    // ── the host ───────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public GroupView lock(String hostId, String groupId, Tip tip) {
        var g = host(hostId, groupId);
        GroupOrderRules.requireOpen(g.state());
        var items = store.items(groupId);
        if (items.isEmpty()) {
            throw RuleViolation.of("items", "required", GroupOrderRules.EMPTY);
        }
        var withTip = g.withTipKind(tip.kind()).withTipValue(tip.value());
        // every S-57 rule on the whole cart: kitchen open, mode, minimum, dishes still orderable, the tip
        var priced = food.priceGroup(hostId, order(withTip, items));
        var now = clock.instant();
        var locked = withTip.withState("locked").withLocksAt(now);
        if (g.payment().equals("split")) {
            locked = shareOut(locked, items, priced);
        }
        store.update(locked);
        return view(locked, hostId);
    }

    /**
     * The split bill: one share per person with dishes, the host's first (its id is the order's). Dishes priced per
     * person from the live menu, the fees shared out ({@link GroupOrderRules#split}), the dishes' tax per share from
     * Stripe Tax (so each card is charged exactly its share's tax).
     */
    private GroupRow shareOut(GroupRow g, List<ItemRow> items, FoodCheckoutService.GroupPricing priced) {
        var byUser = new LinkedHashMap<String, List<ItemRow>>();
        Stream.concat(Stream.of(g.hostId()), g.members().stream()).distinct().forEach(u -> {
            var own = items.stream().filter(i -> i.userId().equals(u)).toList();
            if (!own.isEmpty()) {
                byUser.put(u, own);
            }
        });
        if (!byUser.containsKey(g.hostId())) {
            throw RuleViolation.of("items", "required", GroupOrderRules.HOST_ITEMS);
        }
        var at = Objects.requireNonNullElse(g.scheduledFor(), clock.instant());
        var users = List.copyOf(byUser.keySet());
        var subtotals = users.stream()
                .map(u -> {
                    var o = order(g, Objects.requireNonNull(byUser.get(u)));
                    return pricing.price(new FoodMenuPricing.Request(
                                    g.merchantId(),
                                    at,
                                    o.items().stream()
                                            .map(i -> new FoodMenuPricing.ItemLine(
                                                    i.itemId(), i.qty(), i.optionIds(), i.note()))
                                            .toList(),
                                    o.combos().stream()
                                            .map(c -> new FoodMenuPricing.ComboLine(c.comboId(), c.qty(), c.itemIds()))
                                            .toList()))
                            .subtotalCents();
                })
                .toList();
        var t = priced.totals();
        var delivery = GroupOrderRules.split(
                t.deliveryFeeCents(), users.stream().map(_ -> 1L).toList());
        var service = GroupOrderRules.split(t.serviceFeeCents(), subtotals);
        var fees = new ArrayList<Long>();
        for (var i = 0; i < users.size(); i++) {
            fees.add(delivery.get(i) + service.get(i));
        }
        var feeTax = GroupOrderRules.split(t.feeTaxCents(), fees);
        var orderId = Ids.next();
        for (var i = 0; i < users.size(); i++) {
            var u = users.get(i);
            var quote = taxes.calculate(new TaxCalculations.Request(
                    g.merchantId(), EscrowKind.FOOD, priced.province(), priced.postalCode(), subtotals.get(i)));
            store.insertShare(new ShareRow(
                    i == 0 ? orderId : Ids.next(),
                    g.id(),
                    u,
                    null,
                    "due",
                    subtotals.get(i),
                    delivery.get(i),
                    service.get(i),
                    feeTax.get(i),
                    quote.taxCents(),
                    i == 0 ? t.tipCents() : 0,
                    quote.calculationId(),
                    null,
                    null));
        }
        return g.withOrderId(orderId).withOrderRef(checkouts.nextRef());
    }

    @Override
    @Transactional
    public GroupView unlock(String hostId, String groupId) {
        var g = host(hostId, groupId);
        if (g.state().equals("open")) {
            return view(g, hostId);
        }
        requireLocked(g);
        if (store.shares(groupId).stream().anyMatch(s -> s.state().equals("authorized"))) {
            throw GroupOrderRules.sharesPaid();
        }
        store.deleteShares(groupId);
        var open = g.withState("open").withLocksAt(null).withOrderId(null).withOrderRef(null);
        store.update(open);
        return view(open, hostId);
    }

    @Override
    @Transactional
    public GroupView cancel(String hostId, String groupId) {
        var g = host(hostId, groupId);
        return switch (g.state()) {
            case "placed" -> throw GroupOrderRules.notCancellable();
            case "cancelled", "expired" -> view(g, hostId);
            default -> view(close(g, "cancelled"), hostId);
        };
    }

    /** Closes an unfinished group: every authorized share goes back to its card (the hold is voided). */
    GroupRow close(GroupRow g, String state) {
        var ref = Objects.requireNonNullElse(g.orderRef(), g.code());
        for (var share : store.shares(g.id())) {
            if (share.state().equals("authorized")) {
                refunds.refundNow(REF_TYPE, share.id(), "order_cancelled", "Group order " + ref + " cancelled");
            }
            if (!share.state().equals("voided")) {
                store.updateShare(share.withState("voided"));
            }
        }
        var closed = g.withState(state).withClosedAt(clock.instant());
        store.update(closed);
        return closed;
    }

    @Override
    @Transactional
    public GroupView place(String hostId, String groupId) {
        var g = host(hostId, groupId);
        requirePayment(g, "split");
        if (g.state().equals("placed")) {
            return view(g, hostId);
        }
        requireLocked(g);
        var shares = store.shares(groupId);
        if (shares.isEmpty() || !shares.stream().allMatch(s -> s.state().equals("authorized"))) {
            throw GroupOrderRules.unpaid();
        }
        var amounts = new FoodCheckoutService.GroupAmounts(
                sum(shares, ShareRow::subtotalCents),
                sum(shares, ShareRow::deliveryFeeCents),
                sum(shares, ShareRow::serviceFeeCents),
                sum(shares, ShareRow::feeTaxCents),
                sum(shares, ShareRow::taxCents),
                sum(shares, ShareRow::tipCents));
        var orderId = Objects.requireNonNull(g.orderId());
        var ref = Objects.requireNonNull(g.orderRef());
        food.placeGroup(hostId, orderId, ref, order(g, store.items(groupId)), amounts);
        return placed(g, orderId, ref);
    }

    // ── paying ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Started checkout(
            String hostId,
            boolean mfa,
            @Nullable String stepUpProof,
            String groupId,
            @Nullable Tip tip,
            String clientKey,
            Locale locale) {
        var g = host(hostId, groupId);
        requirePayment(g, "host");
        requireLocked(g);
        var withTip = tip == null ? g : g.withTipKind(tip.kind()).withTipValue(tip.value());
        var started = food.start(hostId, mfa, stepUpProof, order(withTip, store.items(groupId)), clientKey, locale);
        store.update(withTip.withState("paying").withOrderId(started.orderId()).withOrderRef(started.ref()));
        return started;
    }

    @Override
    @Transactional
    public GroupView confirm(String hostId, String groupId) {
        var g = host(hostId, groupId);
        requirePayment(g, "host");
        if (g.state().equals("placed")) {
            return view(g, hostId);
        }
        if (!g.state().equals("paying") || g.orderId() == null) {
            requireLocked(g);
            throw new Conflict("checkout_closed", "Pay for the order first.");
        }
        var placed = food.place(hostId, g.orderId());
        return placed(g, placed.orderId(), placed.ref());
    }

    @Override
    @Transactional
    public ShareStarted payShare(
            String payerId,
            boolean mfa,
            @Nullable String stepUpProof,
            String groupId,
            String forUserId,
            String clientKey) {
        var g = member(payerId, groupId);
        requirePayment(g, "split");
        requireLocked(g);
        var share = shareOf(g, payerId, forUserId);
        if (share.state().equals("authorized")) {
            throw GroupOrderRules.sharePaid();
        }
        // S-51's payment rule, as for any food order
        if (!mfa && !stepUp.verified(payerId, stepUpProof)) {
            throw secondFactors.hasSecondFactor(payerId)
                    ? new StepUpNeeded("step_up_required", CheckoutMessages.STEP_UP)
                    : new StepUpNeeded("second_factor_required", CheckoutMessages.ENROL);
        }
        var started = payments.start(new PaymentAuthorizations.Request(
                g.merchantId(),
                REF_TYPE,
                share.id(),
                payerId,
                share.subtotalCents(),
                share.taxCents(),
                "order:" + g.orderId(),
                clientKey,
                share.taxCalculationId(),
                share.platformCents()));
        store.updateShare(share.withState("paying").withPaidBy(payerId).withPaymentIntent(started.paymentIntent()));
        if (g.state().equals("locked")) {
            store.update(g.withState("paying"));
        }
        return new ShareStarted(
                share.id(),
                share.totalCents(),
                started.paymentIntent(),
                started.clientSecret(),
                started.status(),
                settings.provider(),
                settings.publishableKey());
    }

    @Override
    @Transactional
    public GroupView confirmShare(String payerId, String groupId, String forUserId) {
        var g = member(payerId, groupId);
        requirePayment(g, "split");
        var share = shareOf(g, payerId, forUserId);
        if (share.state().equals("authorized")) {
            return view(g, payerId);
        }
        if (!share.state().equals("paying") || !payerId.equals(share.paidBy()) || share.paymentIntent() == null) {
            requireLocked(g);
            throw new Conflict("checkout_closed", "Pay for the share first.");
        }
        var payer = people.people(List.of(payerId)).get(payerId);
        var ref = Objects.requireNonNull(g.orderRef());
        var first = store.items(g.id()).stream()
                .filter(i -> i.userId().equals(forUserId))
                .findFirst();
        escrow.hold(new EscrowLifecycle.Hold(
                g.merchantId(),
                EscrowKind.FOOD,
                REF_TYPE,
                share.id(),
                share.subtotalCents(),
                share.taxCents(),
                payerId,
                payer == null ? "Customer" : payer.shortName(),
                "Group order " + ref,
                ref,
                null,
                first.map(ItemRow::title).orElse(null),
                "search",
                share.paymentIntent(),
                clock.instant(),
                new EscrowLifecycle.PlatformCharges(
                        share.deliveryFeeCents() + share.serviceFeeCents(), share.feeTaxCents(), share.tipCents())));
        store.updateShare(share.withState("authorized").withAuthorizedAt(clock.instant()));
        return view(g, payerId);
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public GroupView view(String userId, String groupId) {
        var g = store.find(groupId)
                .filter(r -> r.member(userId))
                .orElseThrow(() -> new NotFound("group_order", groupId));
        return view(g, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupView> active(String userId) {
        return store.active(userId).stream().map(g -> view(g, userId)).toList();
    }

    /** Closes groups nobody finished within {@link GroupOrderRules#LIFETIME} (the hourly job). */
    @Transactional
    int expire(Instant now) {
        var n = 0;
        for (var id : store.staleSince(now.minus(GroupOrderRules.LIFETIME))) {
            var g = store.lock(id).orElse(null);
            if (g != null && GroupOrderRules.ACTIVE.contains(g.state())) {
                close(g, "expired");
                n++;
            }
        }
        return n;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private GroupView placed(GroupRow g, String orderId, String ref) {
        store.linkOrder(g.id(), orderId);
        var placed =
                g.withState("placed").withOrderId(orderId).withOrderRef(ref).withPlacedAt(clock.instant());
        store.update(placed);
        return view(placed, g.hostId());
    }

    private GroupRow byCode(String code) {
        var normal = GroupOrderRules.normalizeCode(code);
        return store.byCode(normal).orElseThrow(() -> new NotFound("group_order", normal));
    }

    /** The group locked for this transaction; someone not in it gets 404 (no hint that it exists). */
    private GroupRow member(String userId, String groupId) {
        return store.lock(groupId)
                .filter(g -> g.member(userId))
                .orElseThrow(() -> new NotFound("group_order", groupId));
    }

    private GroupRow host(String hostId, String groupId) {
        var g = member(hostId, groupId);
        if (!g.hostId().equals(hostId)) {
            throw new GroupForbidden("not_host", "Only the host can do that.");
        }
        return g;
    }

    private static void requirePayment(GroupRow g, String payment) {
        if (!g.payment().equals(payment)) {
            throw GroupOrderRules.wrongPayment(g.payment());
        }
    }

    private static void requireLocked(GroupRow g) {
        switch (g.state()) {
            case "locked", "paying" -> {}
            case "open" -> throw GroupOrderRules.notLocked();
            case "placed" -> throw new Conflict("group_placed", "This group order is with the kitchen now.");
            default -> throw GroupOrderRules.closed();
        }
    }

    private ShareRow shareOf(GroupRow g, String payerId, String forUserId) {
        if (!payerId.equals(forUserId) && !payerId.equals(g.hostId())) {
            throw new GroupForbidden("not_your_share", "Pay your own share. Only the host can pay for someone else.");
        }
        return store.shares(g.id()).stream()
                .filter(s -> s.userId().equals(forUserId))
                .findFirst()
                .orElseThrow(() -> new NotFound("group_order_share", forUserId));
    }

    private static long sum(List<ShareRow> shares, java.util.function.ToLongFunction<ShareRow> f) {
        return shares.stream().mapToLong(f).sum();
    }

    private FoodCheckout.Order order(GroupRow g, List<ItemRow> items) {
        return new FoodCheckout.Order(
                g.merchantId(),
                g.mode(),
                g.scheduledFor(),
                items.stream()
                        .filter(i -> i.menuItemId() != null)
                        .map(i -> new FoodCheckout.ItemLine(
                                Objects.requireNonNull(i.menuItemId()), i.qty(), i.optionIds(), i.note()))
                        .toList(),
                items.stream()
                        .filter(i -> i.comboId() != null)
                        .map(i -> new FoodCheckout.ComboLine(
                                Objects.requireNonNull(i.comboId()), i.qty(), i.comboItemIds()))
                        .toList(),
                new Tip(g.tipKind(), g.tipValue()),
                g.delivery() == null ? null : json.readValue(g.delivery(), FoodCheckout.Delivery.class));
    }

    private GroupView view(GroupRow g, String you) {
        var items = store.items(g.id());
        var shares = g.payment().equals("split") ? store.shares(g.id()) : List.<ShareRow>of();
        var ids = Stream.concat(
                        g.members().stream(),
                        shares.stream().map(ShareRow::paidBy).filter(Objects::nonNull))
                .distinct()
                .toList();
        var names = people.people(ids);
        var members = Stream.concat(Stream.of(g.hostId()), g.members().stream())
                .distinct()
                .map(u -> {
                    var own = items.stream()
                            .filter(i -> i.userId().equals(u))
                            .map(this::itemView)
                            .toList();
                    var share = shares.stream()
                            .filter(s -> s.userId().equals(u))
                            .findFirst()
                            .map(s -> new Share(
                                    s.subtotalCents(),
                                    s.deliveryFeeCents(),
                                    s.serviceFeeCents(),
                                    s.feeTaxCents(),
                                    s.taxCents(),
                                    s.tipCents(),
                                    s.totalCents(),
                                    s.state(),
                                    s.paidBy() == null || s.paidBy().equals(u) ? null : nameOf(names, s.paidBy())))
                            .orElse(null);
                    return new Member(
                            u,
                            nameOf(names, u),
                            u.equals(g.hostId()),
                            own,
                            own.stream().mapToLong(ItemView::totalCents).sum(),
                            share);
                })
                .toList();
        var canPlace = g.payment().equals("split")
                && GroupOrderRules.ACTIVE.contains(g.state())
                && !shares.isEmpty()
                && shares.stream().allMatch(s -> s.state().equals("authorized"));
        return new GroupView(
                g.id(),
                g.code(),
                g.state(),
                g.payment(),
                g.merchantId(),
                g.kitchenName(),
                g.kitchenSlug(),
                g.mode(),
                g.scheduledFor(),
                area(g),
                g.hostId(),
                you,
                you.equals(g.hostId()),
                members,
                items.stream().mapToLong(ItemRow::totalCents).sum(),
                g.groupMax(),
                new Tip(g.tipKind(), g.tipValue()),
                g.orderId(),
                g.orderRef(),
                canPlace,
                tracking(g),
                g.createdAt(),
                g.createdAt().plus(GroupOrderRules.LIFETIME));
    }

    private FoodCheckout.@Nullable Tracking tracking(GroupRow g) {
        if (!g.state().equals("placed") || g.orderId() == null) {
            return null;
        }
        try {
            return food.track(g.hostId(), g.orderId());
        } catch (NotFound _) {
            return null; // the order row isn't there (a dev seed group): no tracking
        }
    }

    private ItemView itemView(ItemRow i) {
        return new ItemView(
                i.id(),
                i.menuItemId(),
                i.comboId(),
                i.title(),
                i.qty(),
                i.unitCents(),
                i.totalCents(),
                json.readValue(i.choices(), STRINGS),
                i.note());
    }

    private static String nameOf(java.util.Map<String, PersonDirectory.Person> names, String userId) {
        var p = names.get(userId);
        return p == null ? "Guest" : p.shortName();
    }

    /** Guests see where it goes (zone or city), never the host's street. */
    private @Nullable String area(GroupRow g) {
        if (g.delivery() == null) {
            return null;
        }
        var d = json.readValue(g.delivery(), FoodCheckout.Delivery.class);
        return d.zone() != null ? d.zone() : d.city();
    }
}
