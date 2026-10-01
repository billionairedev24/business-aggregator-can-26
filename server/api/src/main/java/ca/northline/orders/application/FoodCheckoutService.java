package ca.northline.orders.application;

import ca.northline.food.api.FoodCheckoutFacts;
import ca.northline.food.api.FoodMenuPricing;
import ca.northline.food.api.KitchenProgress;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.SecondFactors;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.orders.application.FoodCheckout.Line;
import ca.northline.orders.application.FoodCheckout.Order;
import ca.northline.orders.application.FoodCheckout.PlaceFoodOrder;
import ca.northline.orders.application.FoodCheckout.Placed;
import ca.northline.orders.application.FoodCheckout.QuoteFoodOrder;
import ca.northline.orders.application.FoodCheckout.StartFoodOrder;
import ca.northline.orders.application.FoodCheckout.Started;
import ca.northline.orders.application.FoodCheckout.Totals;
import ca.northline.orders.application.FoodCheckout.TrackFoodOrder;
import ca.northline.orders.application.FoodCheckout.Tracking;
import ca.northline.orders.application.FoodCheckoutStore.CheckoutRow;
import ca.northline.orders.application.FoodCheckoutStore.OrderLineRow;
import ca.northline.orders.domain.CheckoutMessages;
import ca.northline.orders.domain.FoodOrderRules;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.PaymentStepUp;
import ca.northline.payments.api.TaxCalculations;
import ca.northline.region.api.Markets;
import ca.northline.region.api.TaxRates;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Food checkout (S-57). Prices come from the kitchen's live menu ({@link FoodMenuPricing}), times and fees from
 * {@link FoodCheckoutFacts}; the dishes' tax from Stripe Tax ({@link TaxCalculations}, place of supply = the delivery
 * address, or the kitchen for pickup), the fees' tax from the province's rates. One manual-capture PaymentIntent per
 * food order (refType {@code food_order}) carries the kitchen's escrow plus Northline's own charges; the order reaches
 * the kitchen display only when {@link EscrowLifecycle#hold} accepts the authorization, and the hold is released on
 * {@code order.handed_off} ("food on handoff").
 */
@Service
class FoodCheckoutService implements QuoteFoodOrder, StartFoodOrder, PlaceFoodOrder, TrackFoodOrder {

    static final String REF_TYPE = "food_order";
    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {};

    private final FoodMenuPricing pricing;
    private final FoodCheckoutFacts kitchens;
    private final KitchenProgress progress;
    private final PaymentAuthorizations payments;
    private final EscrowLifecycle escrow;
    private final TaxCalculations taxes;
    private final TaxRates rates;
    private final PersonDirectory people;
    private final FoodCheckoutStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecondFactors secondFactors;
    private final PaymentStepUp stepUp;
    private final PaymentSettings settings;
    private final Markets markets;
    private final JsonMapper json = JsonMapper.builder().build();

    FoodCheckoutService(
            FoodMenuPricing pricing,
            FoodCheckoutFacts kitchens,
            KitchenProgress progress,
            PaymentAuthorizations payments,
            EscrowLifecycle escrow,
            TaxCalculations taxes,
            TaxRates rates,
            PersonDirectory people,
            FoodCheckoutStore store,
            ApplicationEventPublisher events,
            Clock clock,
            SecondFactors secondFactors,
            PaymentStepUp stepUp,
            PaymentSettings settings,
            Markets markets) {
        this.pricing = pricing;
        this.kitchens = kitchens;
        this.progress = progress;
        this.payments = payments;
        this.escrow = escrow;
        this.taxes = taxes;
        this.rates = rates;
        this.people = people;
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.secondFactors = secondFactors;
        this.stepUp = stepUp;
        this.settings = settings;
        this.markets = markets;
    }

    /** Everything decided before any money moves: the kitchen, the priced lines, fees, tip, place of supply. */
    private record Priced(
            FoodCheckoutFacts.Kitchen kitchen,
            FoodMenuPricing.Priced dishes,
            String province,
            long deliveryFeeCents,
            long serviceFeeCents,
            long feeTaxCents,
            long tipCents,
            @Nullable Integer etaFromMin,
            @Nullable Integer etaToMin) {}

    @Override
    @Transactional(readOnly = true)
    public Totals quote(String customerId, Order order) {
        var p = price(order);
        var tax = Math.round(p.dishes().subtotalCents() * rates.bpsFor(p.province()) / 10_000.0);
        return totals(order, p, tax, true);
    }

    @Override
    @Transactional
    public Started start(
            String customerId,
            boolean mfa,
            @Nullable String stepUpProof,
            Order order,
            String clientKey,
            Locale locale) {
        // S-51's payment rule: a phone-code sign-in confirms with its second factor first, or enrols one
        if (!mfa && !stepUp.verified(customerId, stepUpProof)) {
            throw secondFactors.hasSecondFactor(customerId)
                    ? new StepUpNeeded("step_up_required", CheckoutMessages.STEP_UP)
                    : new StepUpNeeded("second_factor_required", CheckoutMessages.ENROL);
        }
        var p = price(order);
        var postal = order.delivery() == null ? null : order.delivery().postalCode();
        var quote = taxes.calculate(new TaxCalculations.Request(
                p.kitchen().merchantId(),
                EscrowKind.FOOD,
                p.province(),
                postal,
                p.dishes().subtotalCents()));
        var totals = totals(order, p, quote.taxCents(), false);
        var id = Ids.next();
        var now = clock.instant();
        var ref = store.nextRef();
        store.insert(new CheckoutRow(
                id,
                ref,
                customerId,
                p.kitchen().merchantId(),
                p.kitchen().name(),
                p.kitchen().slug(),
                "pending",
                order.mode(),
                order.scheduledFor(),
                customerEta(order, p, now),
                p.etaFromMin(),
                p.etaToMin(),
                json.writeValueAsString(totals.lines()),
                p.dishes().subtotalCents(),
                p.deliveryFeeCents(),
                p.serviceFeeCents(),
                p.feeTaxCents(),
                quote.taxCents(),
                p.tipCents(),
                totals.totalCents(),
                p.province(),
                quote.calculationId(),
                null,
                order.delivery() == null ? null : json.writeValueAsString(order.delivery()),
                now,
                null));
        var started = payments.start(new PaymentAuthorizations.Request(
                p.kitchen().merchantId(),
                REF_TYPE,
                id,
                customerId,
                p.dishes().subtotalCents(),
                quote.taxCents(),
                "order:" + id,
                clientKey,
                quote.calculationId(),
                p.deliveryFeeCents() + p.serviceFeeCents() + p.feeTaxCents() + p.tipCents()));
        store.paymentStarted(id, started.paymentIntent());
        return new Started(
                id,
                ref,
                totals,
                started.paymentIntent(),
                started.clientSecret(),
                started.status(),
                settings.provider(),
                settings.publishableKey());
    }

    @Override
    @Transactional
    public Placed place(String customerId, String orderId) {
        var row = own(customerId, orderId);
        if (row.state().equals("placed")) {
            return new Placed(row.id(), row.ref());
        }
        if (!row.state().equals("pending") || row.paymentIntent() == null) {
            throw new Conflict("checkout_closed", "This checkout can't be paid any more. Start again from the menu.");
        }
        var now = clock.instant();
        var name = people.people(List.of(customerId)).get(customerId);
        var lines = json.readValue(row.lines(), LINES);
        escrow.hold(new EscrowLifecycle.Hold(
                row.merchantId(),
                EscrowKind.FOOD,
                REF_TYPE,
                row.id(),
                row.subtotalCents(),
                row.taxCents(),
                customerId,
                name == null ? "Customer" : name.shortName(),
                "Food order " + row.ref(),
                row.ref(),
                null,
                lines.isEmpty() ? null : lines.getFirst().title(),
                "search", // attribution (payments.escrows.source), as S-51
                row.paymentIntent(),
                now,
                new EscrowLifecycle.PlatformCharges(
                        row.deliveryFeeCents() + row.serviceFeeCents(), row.feeTaxCents(), row.tipCents())));
        var orderLines = lines.stream()
                .map(l -> new OrderLineRow(
                        Ids.next(), l.itemId(), l.comboId(), l.title(), l.qty(), l.unitCents(), modifiers(l)))
                .toList();
        if (store.place(row, orderLines, now)) {
            // S-51's event; one kitchen per food order, "direct" = the hot courier
            events.publishEvent(new OrderPlaced(
                    Ids.next(),
                    now,
                    row.id(),
                    row.merchantId(),
                    customerId,
                    row.ref(),
                    "food",
                    row.fulfilmentMode().equals("pickup") ? "pickup" : "direct",
                    null,
                    row.subtotalCents(),
                    row.taxCents(),
                    orderLines.stream()
                            .map(l -> new OrderPlaced.Line(
                                    l.id(),
                                    Objects.requireNonNullElse(
                                            l.menuItemId(), Objects.requireNonNullElse(l.comboId(), "")),
                                    null,
                                    l.qty(),
                                    l.unitCents() * l.qty(),
                                    l.menuItemId() != null ? "menu_item" : "combo"))
                            .toList()));
        }
        return new Placed(row.id(), row.ref());
    }

    @Override
    @Transactional(readOnly = true)
    public Tracking track(String customerId, String orderId) {
        var row = own(customerId, orderId);
        if (!row.state().equals("placed")) {
            throw new NotFound("order", orderId);
        }
        var state = store.state(orderId).orElseThrow(() -> new NotFound("order", orderId));
        var ticket = progress.of(orderId).orElse(null);
        var stage = FoodOrderRules.stage(state.state(), ticket == null ? null : ticket.stage(), row.fulfilmentMode());
        return new Tracking(
                row.id(),
                row.ref(),
                row.kitchenName(),
                row.kitchenSlug(),
                row.fulfilmentMode(),
                state.state(),
                stage,
                Objects.requireNonNull(row.placedAt()),
                row.scheduledFor(),
                ticket == null ? null : ticket.acceptedAt(),
                ticket == null ? null : ticket.prepMin(),
                ticket == null ? null : ticket.readyBy(),
                ticket == null ? null : ticket.readyAt(),
                ticket == null ? null : ticket.handedOffAt(),
                state.deliveredAt(),
                eta(row, ticket),
                row.totalCents(),
                json.readValue(row.lines(), LINES));
    }

    // ── pricing ────────────────────────────────────────────────────────────────────────────────────────────

    private Priced price(Order order) {
        var isDelivery = order.mode().equals("delivery");
        if (!isDelivery && !order.mode().equals("pickup")) {
            throw RuleViolation.of("mode", "required", FoodOrderRules.MODE);
        }
        var delivery = isDelivery
                ? Objects.requireNonNullElseGet(order.delivery(), () -> {
                    throw RuleViolation.of("delivery", "required", FoodOrderRules.ADDRESS_REQUIRED);
                })
                : null;
        if (delivery != null) {
            FoodOrderRules.checkDelivery(delivery.dropoff(), delivery.extras(), delivery.note(), delivery.unit());
        }
        var kitchen = kitchens.kitchen(
                        order.merchantId(),
                        delivery == null ? null : delivery.lat(),
                        delivery == null ? null : delivery.lng())
                .orElseThrow(() -> new NotFound("kitchen", order.merchantId()));
        if (isDelivery && !kitchen.fulfilment().contains("courier")) {
            throw new Conflict("no_delivery", kitchen.name() + " doesn't deliver. Choose pickup.");
        }
        if (isDelivery && Boolean.FALSE.equals(kitchen.delivers())) {
            throw new Conflict(
                    "out_of_range",
                    kitchen.name() + " doesn't deliver to this address. Choose pickup or another address.");
        }
        if (!isDelivery && !kitchen.fulfilment().contains("pickup")) {
            throw new Conflict("no_pickup", kitchen.name() + " doesn't offer pickup.");
        }
        var at = order.scheduledFor();
        if (at == null) {
            if (!kitchen.open()) {
                throw new Conflict(
                        "kitchen_closed",
                        kitchen.paused()
                                ? kitchen.name()
                                        + " isn't taking orders right now. Try again in a few minutes, or schedule for later."
                                : kitchen.name() + " is closed right now. Schedule for later.");
            }
        } else if (!kitchen.slots().contains(at)) {
            throw RuleViolation.of("scheduledFor", "slot", FoodOrderRules.SLOT);
        }
        var dishes = pricing.price(new FoodMenuPricing.Request(
                kitchen.merchantId(),
                at == null ? clock.instant() : at,
                order.items().stream()
                        .map(i -> new FoodMenuPricing.ItemLine(i.itemId(), i.qty(), i.optionIds(), i.note()))
                        .toList(),
                order.combos().stream()
                        .map(c -> new FoodMenuPricing.ComboLine(c.comboId(), c.qty(), c.itemIds()))
                        .toList()));
        if (dishes.subtotalCents() < kitchen.minOrderCents()) {
            throw RuleViolation.of(
                    "items", "minimum", FoodOrderRules.belowMinimum(kitchen.minOrderCents() - dishes.subtotalCents()));
        }
        // place of supply: the delivery address, else (pickup) the kitchen, else the default market's province
        var province = delivery != null
                ? delivery.province()
                : Objects.requireNonNullElse(
                        kitchen.province(), Objects.requireNonNullElse(markets.defaultProvince(), ""));
        if (!FoodOrderRules.PROVINCE.matcher(province).matches()) {
            throw RuleViolation.of("delivery.province", "format", FoodOrderRules.PROVINCE_FORMAT);
        }
        var deliveryFee = isDelivery ? kitchen.deliveryFeeCents() : 0;
        var serviceFee = Math.round(dishes.subtotalCents() * kitchen.serviceFeeBps() / 10_000.0);
        var feeTax = Math.round((deliveryFee + serviceFee) * rates.bpsFor(province) / 10_000.0);
        var tip = isDelivery
                ? FoodOrderRules.tipCents(order.tip().kind(), order.tip().value(), dishes.subtotalCents())
                : 0;
        Integer from = null;
        Integer to = null;
        if (at == null) {
            from = isDelivery
                    ? kitchen.etaFromMin() + dishes.prepAddMin()
                    : kitchen.pickupFromMin() + dishes.prepAddMin();
            to = isDelivery ? kitchen.etaToMin() + dishes.prepAddMin() : kitchen.pickupToMin() + dishes.prepAddMin();
        }
        return new Priced(kitchen, dishes, province, deliveryFee, serviceFee, feeTax, tip, from, to);
    }

    private static Totals totals(Order order, Priced p, long dishTax, boolean estimate) {
        var lines = p.dishes().lines().stream()
                .map(l -> new Line(
                        l.itemId(),
                        l.comboId(),
                        l.title(),
                        l.qty(),
                        l.unitCents(),
                        l.totalCents(),
                        l.choices().stream().map(FoodMenuPricing.Choice::name).toList(),
                        l.note()))
                .toList();
        var tax = dishTax + p.feeTaxCents();
        return new Totals(
                p.kitchen().name(),
                order.mode(),
                order.scheduledFor(),
                lines,
                p.dishes().subtotalCents(),
                p.deliveryFeeCents(),
                p.serviceFeeCents(),
                p.tipCents(),
                tax,
                p.feeTaxCents(),
                p.dishes().subtotalCents() + p.deliveryFeeCents() + p.serviceFeeCents() + p.tipCents() + tax,
                p.etaFromMin(),
                p.etaToMin(),
                estimate);
    }

    /** Pickup: when the customer is expected at the counter (ready time); delivery: none (the courier's). */
    private static @Nullable Instant customerEta(Order order, Priced p, Instant now) {
        if (!order.mode().equals("pickup")) {
            return null;
        }
        return order.scheduledFor() != null
                ? order.scheduledFor()
                : now.plus(Duration.ofMinutes(
                        Objects.requireNonNullElse(p.etaFromMin(), p.kitchen().pickupFromMin())));
    }

    private static @Nullable Instant eta(CheckoutRow row, KitchenProgress.@Nullable Ticket ticket) {
        if (row.scheduledFor() != null) {
            return row.scheduledFor();
        }
        var placed = Objects.requireNonNull(row.placedAt());
        if (ticket != null && ticket.readyBy() != null && row.fulfilmentMode().equals("delivery")) {
            var ride = Math.max(
                    5,
                    Objects.requireNonNullElse(row.etaFromMin(), 30)
                            - Objects.requireNonNullElse(ticket.prepMin(), 25));
            return ticket.readyBy().plus(Duration.ofMinutes(ride));
        }
        if (ticket != null && ticket.readyBy() != null) {
            return ticket.readyBy();
        }
        return placed.plus(Duration.ofMinutes(Objects.requireNonNullElse(row.etaToMin(), 40)));
    }

    private CheckoutRow own(String customerId, String orderId) {
        return store.find(orderId)
                .filter(r -> r.customerId().equals(customerId))
                .orElseThrow(() -> new NotFound("order", orderId));
    }

    /** What the kitchen display prints under the dish: the choices, then the customer's note. */
    private String modifiers(Line line) {
        var out = new ArrayList<Map<String, String>>();
        line.choices().forEach(c -> out.add(Map.of("name", c)));
        if (line.note() != null) {
            var note = new LinkedHashMap<String, String>();
            note.put("name", "Note: " + line.note());
            out.add(note);
        }
        return json.writeValueAsString(out);
    }
}
