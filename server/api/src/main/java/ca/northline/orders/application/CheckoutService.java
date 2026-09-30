package ca.northline.orders.application;

import static ca.northline.orders.domain.CheckoutMessages.ADDRESS;
import static ca.northline.orders.domain.CheckoutMessages.CART_EMPTY;
import static ca.northline.orders.domain.CheckoutMessages.CITY;
import static ca.northline.orders.domain.CheckoutMessages.ENROL;
import static ca.northline.orders.domain.CheckoutMessages.EXPIRED;
import static ca.northline.orders.domain.CheckoutMessages.NOTE;
import static ca.northline.orders.domain.CheckoutMessages.NOT_AUTHORIZED;
import static ca.northline.orders.domain.CheckoutMessages.NOT_SERVED;
import static ca.northline.orders.domain.CheckoutMessages.OUT_OF_STOCK;
import static ca.northline.orders.domain.CheckoutMessages.POSTAL;
import static ca.northline.orders.domain.CheckoutMessages.PROVINCE;
import static ca.northline.orders.domain.CheckoutMessages.SHOP_ELSEWHERE;
import static ca.northline.orders.domain.CheckoutMessages.STEP_UP;
import static ca.northline.orders.domain.CheckoutMessages.STREET;
import static ca.northline.orders.domain.CheckoutMessages.SUBSTITUTION;
import static ca.northline.orders.domain.CheckoutMessages.UNIT;
import static ca.northline.orders.domain.CheckoutMessages.WINDOW;
import static ca.northline.orders.domain.CheckoutMessages.WINDOW_CLOSED;

import ca.northline.identity.api.DeliveryAddresses;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.SecondFactors;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.orders.api.SellableOffers;
import ca.northline.orders.application.CartUseCases.CartLine;
import ca.northline.orders.application.CartUseCases.CartOwner;
import ca.northline.orders.application.CartUseCases.CartView;
import ca.northline.orders.application.CheckoutStore.Checkout;
import ca.northline.orders.application.CheckoutStore.Line;
import ca.northline.orders.application.CheckoutUseCases.AddressInput;
import ca.northline.orders.application.CheckoutUseCases.AddressView;
import ca.northline.orders.application.CheckoutUseCases.Intent;
import ca.northline.orders.application.CheckoutUseCases.Option;
import ca.northline.orders.application.CheckoutUseCases.Payment;
import ca.northline.orders.application.CheckoutUseCases.Placed;
import ca.northline.orders.application.CheckoutUseCases.Quote;
import ca.northline.orders.application.CheckoutUseCases.Request;
import ca.northline.orders.application.CheckoutUseCases.Setup;
import ca.northline.orders.application.CheckoutUseCases.Started;
import ca.northline.orders.application.CheckoutUseCases.TaxLine;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.PaymentStepUp;
import ca.northline.payments.api.TaxCalculations;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CheckoutUseCases}. One transaction per step; the stock taken, the tax calculations and the PaymentIntents all
 * belong to the checkout row, so an expired or abandoned checkout gives everything back.
 *
 * <p><b>Payment rule (S-62 follow-up):</b> a sign-in with a second factor ({@code acr=mfa}) pays directly. A phone-code
 * sign-in must send a fresh step-up proof ({@code X-Step-Up}, ≤ 5 minutes, single use) from its passkey or
 * authenticator; an account without either first enrols a passkey (northline-auth issues the proof with it).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CheckoutService
        implements CheckoutUseCases.SetUpCheckout,
                CheckoutUseCases.QuoteCheckout,
                CheckoutUseCases.StartCheckout,
                CheckoutUseCases.PlaceOrder,
                CheckoutUseCases.ExpireCheckouts {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final Duration HOLD = Duration.ofMinutes(30);
    static final Set<String> PROVINCES =
            Set.of("AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");
    static final Set<String> SUBSTITUTIONS = Set.of("similar", "refund", "ask");
    static final Pattern POSTAL_CODE = Pattern.compile("[A-Z]\\d[A-Z] ?\\d[A-Z]\\d");
    static final String DIRECT = "direct";
    static final String POOLED = "pooled";

    private final CartService cart;
    private final CartStore carts;
    private final SellableOffers offers;
    private final ShopDirectory shops;
    private final DeliveryRuns runs;
    private final DeliveryAddresses addresses;
    private final SecondFactors secondFactors;
    private final PaymentStepUp stepUp;
    private final TaxCalculations taxes;
    private final PaymentAuthorizations payments;
    private final PaymentSettings paymentSettings;
    private final EscrowLifecycle escrow;
    private final PersonDirectory people;
    private final CheckoutStore checkouts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── set-up and quote ──────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Setup setup(String userId, boolean mfa, String market, String lang) {
        var view = cart.view(new CartOwner(userId, null), lang);
        var served = runs.market(market);
        var name = served.orElse(market.strip());
        var options = served.map(m -> options(view, m, clock.instant())).orElse(List.of());
        var saved = addresses.of(userId).stream()
                .map(a -> new AddressView(
                        a.id(), a.street(), a.unit(), a.city(), a.province(), a.postal(), a.note(), a.isDefault()))
                .toList();
        return new Setup(
                view,
                saved,
                options,
                new Payment(paymentSettings.provider(), paymentSettings.publishableKey()),
                mfa ? "none" : stepUpNeeded(userId),
                name,
                served.isPresent());
    }

    @Override
    public Quote quote(String userId, Request request, String lang) {
        var plan = plan(userId, request, lang, false);
        var taxed = taxLines(plan);
        var tax = taxed.stream().mapToLong(TaxCalculations.Quote::taxCents).sum();
        var deliveryFee = plan.option().feeCents();
        return new Quote(
                plan.subtotal(),
                deliveryFee,
                tax,
                summarize(taxed),
                plan.subtotal() + deliveryFee + tax,
                plan.market());
    }

    // ── start ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Started start(
            String userId,
            boolean mfa,
            @Nullable String stepUpProof,
            Request request,
            @Nullable String clientKey,
            String lang) {
        if (!mfa && !stepUp.verified(userId, stepUpProof)) {
            throw secondFactors.hasSecondFactor(userId)
                    ? new StepUpNeeded("step_up_required", STEP_UP)
                    : new StepUpNeeded("second_factor_required", ENROL);
        }
        var plan = plan(userId, request, lang, true);
        var now = clock.instant();
        checkouts.open(userId).forEach(this::abandon);

        var takes = plan.lines().stream()
                .map(l -> new SellableOffers.Take(l.offerId(), l.variantId(), l.qty()))
                .toList();
        if (!offers.take(takes).isEmpty()) {
            throw new Conflict("out_of_stock", OUT_OF_STOCK);
        }

        var checkoutId = Ids.next();
        var orderId = Ids.next();
        var ref = checkouts.nextRef();
        var group = "order:" + orderId;
        var province = plan.address().province();
        var postal = plan.address().postal();
        var lines = new ArrayList<Line>();
        var intents = new ArrayList<Intent>();
        for (var l : plan.lines()) {
            var lineId = Ids.next();
            var amount = l.lineCents();
            var taxQuote = taxes.calculate(
                    new TaxCalculations.Request(l.merchantId(), EscrowKind.GOODS, province, postal, amount));
            var started = payments.start(new PaymentAuthorizations.Request(
                    l.merchantId(),
                    "order_line",
                    lineId,
                    userId,
                    amount,
                    taxQuote.taxCents(),
                    group,
                    clientKey,
                    taxQuote.calculationId()));
            intents.add(new Intent(
                    started.paymentIntent(), started.clientSecret(), started.status(), amount + taxQuote.taxCents()));
            lines.add(new Line(
                    lineId,
                    l.offerId(),
                    l.variantId(),
                    l.productId(),
                    l.merchantId(),
                    l.name(),
                    l.option(),
                    l.qty(),
                    l.unitCents(),
                    amount,
                    taxQuote.taxCents(),
                    taxQuote.calculationId(),
                    started.paymentIntent()));
        }
        var fee = plan.option().feeCents();
        var deliveryTax = 0L;
        String deliveryIntent = null;
        if (fee > 0) {
            var feeTax = taxes.calculate(new TaxCalculations.Request(
                    PaymentAuthorizations.PLATFORM, EscrowKind.GOODS, province, postal, fee));
            deliveryTax = feeTax.taxCents();
            var started = payments.start(new PaymentAuthorizations.Request(
                    PaymentAuthorizations.PLATFORM,
                    "order_delivery",
                    orderId,
                    userId,
                    fee,
                    deliveryTax,
                    group,
                    clientKey,
                    feeTax.calculationId()));
            deliveryIntent = started.paymentIntent();
            intents.add(
                    new Intent(started.paymentIntent(), started.clientSecret(), started.status(), fee + deliveryTax));
        }
        var lineTax = lines.stream().mapToLong(Line::taxCents).sum();
        var tax = lineTax + deliveryTax;
        var total = plan.subtotal() + fee + tax;
        var checkout = new Checkout(
                checkoutId,
                userId,
                "open",
                orderId,
                ref,
                plan.market(),
                plan.option().kind(),
                plan.option().windowId(),
                plan.substitution(),
                plan.address().id(),
                province,
                plan.subtotal(),
                fee,
                deliveryTax,
                tax,
                total,
                deliveryIntent,
                lines,
                now,
                now.plus(HOLD));
        checkouts.insert(checkout);
        return new Started(
                checkoutId,
                orderId,
                ref,
                total,
                checkout.expiresAt(),
                new Payment(paymentSettings.provider(), paymentSettings.publishableKey()),
                intents);
    }

    // ── place ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Placed place(String userId, String checkoutId) {
        var checkout = checkouts.find(userId, checkoutId).orElseThrow(() -> new NotFound("checkout", checkoutId));
        if ("placed".equals(checkout.state())) {
            return new Placed(checkout.orderId(), checkout.ref());
        }
        var now = clock.instant();
        if (!"open".equals(checkout.state()) || now.isAfter(checkout.expiresAt())) {
            throw new Conflict("checkout_expired", EXPIRED);
        }
        var deliveryIntent = checkout.deliveryPaymentIntent();
        if (deliveryIntent != null
                && !payments.authorized(deliveryIntent, checkout.deliveryFeeCents() + checkout.deliveryTaxCents())) {
            throw new Conflict("payment_not_authorized", NOT_AUTHORIZED);
        }
        var name = people.people(List.of(userId)).values().stream()
                .findFirst()
                .map(PersonDirectory.Person::shortName)
                .orElse("");
        for (var line : checkout.lines()) {
            escrow.hold(new EscrowLifecycle.Hold(
                    line.merchantId(),
                    EscrowKind.GOODS,
                    "order_line",
                    line.lineId(),
                    line.amountCents(),
                    line.taxCents(),
                    userId,
                    name,
                    line.option() == null ? line.name() : line.name() + " · " + line.option(),
                    checkout.ref(),
                    line.offerId(),
                    line.name(),
                    "search",
                    line.paymentIntent(),
                    now));
        }
        if (!checkouts.placed(checkoutId, now)) {
            throw new Conflict("checkout_expired", EXPIRED);
        }
        var area = addresses
                .find(userId, checkout.addressId())
                .map(DeliveryAddresses.Address::city)
                .orElse(null);
        var scheduled = DIRECT.equals(checkout.kind()) ? now.plus(runs.direct().eta()) : null;
        checkouts.createOrder(checkout, area, scheduled, now);
        publishPlaced(checkout, now);
        carts.cartOf(new CartOwner(userId, null))
                .ifPresent(cartId -> carts.removeLines(
                        cartId,
                        checkout.lines().stream()
                                .map(l -> new CartStore.Item("", l.offerId(), l.variantId(), l.qty(), now))
                                .toList(),
                        now));
        return new Placed(checkout.orderId(), checkout.ref());
    }

    /** {@code order.placed} once per shop, with that shop's lines. */
    private void publishPlaced(Checkout checkout, Instant now) {
        var perShop = checkout.lines().stream()
                .collect(Collectors.groupingBy(Line::merchantId, LinkedHashMap::new, Collectors.toList()));
        perShop.forEach((merchantId, lines) -> events.publishEvent(new OrderPlaced(
                Ids.next(),
                now,
                checkout.orderId(),
                merchantId,
                checkout.customerId(),
                checkout.ref(),
                "goods",
                checkout.kind(),
                checkout.windowId(),
                lines.stream().mapToLong(Line::amountCents).sum(),
                lines.stream().mapToLong(Line::taxCents).sum(),
                lines.stream()
                        .map(l ->
                                new OrderPlaced.Line(l.lineId(), l.offerId(), l.variantId(), l.qty(), l.amountCents()))
                        .toList())));
    }

    // ── expiry ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int expire(Instant now) {
        var due = checkouts.expired(now, 100);
        due.forEach(this::abandon);
        return due.size();
    }

    /** Stock back and the card holds released, once (a concurrent abandon/place wins or loses on the row). */
    private void abandon(Checkout checkout) {
        if (!checkouts.abandon(checkout.id())) {
            return;
        }
        offers.giveBack(checkout.lines().stream()
                .map(l -> new SellableOffers.Take(l.offerId(), l.variantId(), l.qty()))
                .toList());
        var intents = new ArrayList<String>();
        checkout.lines().forEach(l -> intents.add(l.paymentIntent()));
        var delivery = checkout.deliveryPaymentIntent();
        if (delivery != null) {
            intents.add(delivery);
        }
        for (var intent : intents) {
            try {
                payments.cancel(intent);
            } catch (RuntimeException e) {
                // the authorization lapses on its own after 7 days; never block releasing the stock on Stripe
                log.warn("Couldn't cancel {} of abandoned checkout {}: {}", intent, checkout.id(), e.getMessage());
            }
        }
    }

    // ── planning ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** What would be bought, where to and how — validated; {@code save} keeps a new address. */
    private Plan plan(String userId, Request request, String lang, boolean save) {
        var view = cart.view(new CartOwner(userId, null), lang);
        var lines = view.groups().stream().flatMap(g -> g.items().stream()).toList();
        if (lines.isEmpty()) {
            throw new Conflict("cart_empty", CART_EMPTY);
        }
        if (lines.stream().anyMatch(l -> !l.available())) {
            throw new Conflict("out_of_stock", OUT_OF_STOCK);
        }
        if (!SUBSTITUTIONS.contains(request.substitution())) {
            throw RuleViolation.of("substitution", "required", SUBSTITUTION);
        }
        var address = address(userId, request.address(), save);
        var market = runs.market(address.city())
                .orElseThrow(() -> RuleViolation.of("address.city", "served", NOT_SERVED.formatted(address.city())));
        var merchantIds = lines.stream().map(CartLine::merchantId).distinct().toList();
        var sellers =
                shops.shops(merchantIds).stream().collect(Collectors.toMap(ShopDirectory.Shop::merchantId, s -> s));
        for (var merchantId : merchantIds) {
            var shop = sellers.get(merchantId);
            if (shop == null) {
                throw new Conflict("out_of_stock", OUT_OF_STOCK);
            }
            if (!shop.city().equalsIgnoreCase(market)) {
                throw RuleViolation.of("items", "market", SHOP_ELSEWHERE.formatted(shop.displayName(), address.city()));
            }
        }
        var option = options(view, market, clock.instant()).stream()
                .filter(o -> Objects.equals(o.kind(), request.kind())
                        && (DIRECT.equals(o.kind()) || Objects.equals(o.windowId(), request.windowId())))
                .findFirst()
                .orElseThrow(() ->
                        request.kind().isBlank() || !(POOLED.equals(request.kind()) || DIRECT.equals(request.kind()))
                                ? RuleViolation.of("windowId", "required", WINDOW)
                                : new Conflict("window_closed", WINDOW_CLOSED));
        var planned = lines.stream()
                .map(l -> new PlannedLine(
                        l.offerId(),
                        l.variantId(),
                        l.productId(),
                        l.merchantId(),
                        l.name(),
                        l.option(),
                        l.qty(),
                        l.unitCents(),
                        l.lineCents()))
                .toList();
        return new Plan(planned, view.subtotalCents(), option, address, market, request.substitution());
    }

    /**
     * Pooled runs the whole cart can make (the slowest handling time decides; a line that isn't delivered pooled
     * rules pooled runs out), the next two; plus the direct courier when everything is ready the same day.
     */
    List<Option> options(CartView view, String market, Instant now) {
        var lines = view.groups().stream().flatMap(g -> g.items().stream()).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        var handling = lines.stream().map(CartLine::handlingDays).toList();
        var out = new ArrayList<Option>();
        if (handling.stream().allMatch(Objects::nonNull)) {
            var days = handling.stream().mapToInt(Integer::intValue).max().orElse(0);
            var today = LocalDate.ofInstant(now, ZONE);
            runs.upcoming(market, now).stream()
                    .filter(r -> ChronoUnit.DAYS.between(today, LocalDate.ofInstant(r.startsAt(), ZONE)) >= days)
                    .limit(2)
                    .forEach(r -> out.add(new Option(
                            r.windowId(),
                            POOLED,
                            r.windowId(),
                            day(today, r.startsAt()),
                            r.startsAt(),
                            r.endsAt(),
                            r.orderBy(),
                            r.packBy(),
                            r.feeCents(),
                            r.households(),
                            null)));
            if (days == 0) {
                var direct = runs.direct();
                out.add(new Option(DIRECT, DIRECT, null, null, null, null, null, null, direct.feeCents(), 0, (int)
                        direct.eta().toMinutes()));
            }
        }
        return out;
    }

    private static String day(LocalDate today, Instant at) {
        var days = ChronoUnit.DAYS.between(today, LocalDate.ofInstant(at, ZONE));
        return days <= 0 ? "today" : days == 1 ? "tomorrow" : "later";
    }

    private DeliveryAddresses.Address address(String userId, AddressInput input, boolean save) {
        var addressId = input.addressId();
        if (addressId != null && !addressId.isBlank()) {
            return addresses
                    .find(userId, addressId)
                    .orElseThrow(() -> RuleViolation.of("address.addressId", "required", ADDRESS));
        }
        var violations = new ArrayList<Violation>();
        var street = trim(input.street());
        var unit = trim(input.unit());
        var city = trim(input.city());
        var province = trim(input.province());
        var postal = trim(input.postal());
        var note = trim(input.note());
        if (street == null || street.length() > 120) {
            violations.add(new Violation("address.street", "required", STREET));
        }
        if (unit != null && unit.length() > 20) {
            violations.add(new Violation("address.unit", "length", UNIT));
        }
        if (city == null || city.length() > 60) {
            violations.add(new Violation("address.city", "required", CITY));
        }
        var provinceCode = province == null ? null : province.toUpperCase(Locale.ROOT);
        if (provinceCode == null || !PROVINCES.contains(provinceCode)) {
            violations.add(new Violation("address.province", "format", PROVINCE));
        }
        var postalCode = postal == null ? null : postal.toUpperCase(Locale.ROOT).replace('-', ' ');
        if (postalCode == null || !POSTAL_CODE.matcher(postalCode).matches()) {
            violations.add(new Violation("address.postal", "format", POSTAL));
        }
        if (note != null && note.length() > 200) {
            violations.add(new Violation("address.note", "length", NOTE));
        }
        if (!violations.isEmpty()) {
            throw new RuleViolation(violations);
        }
        var normalized = Objects.requireNonNull(postalCode).replace(" ", "");
        var formatted = normalized.substring(0, 3) + " " + normalized.substring(3);
        var fresh = new DeliveryAddresses.NewAddress(
                Objects.requireNonNull(street),
                unit,
                Objects.requireNonNull(city),
                Objects.requireNonNull(provinceCode),
                formatted,
                note);
        if (save) {
            return addresses.save(userId, fresh);
        }
        return new DeliveryAddresses.Address(
                "", fresh.street(), fresh.unit(), fresh.city(), fresh.province(), fresh.postal(), fresh.note(), false);
    }

    private static @Nullable String trim(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<TaxCalculations.Quote> taxLines(Plan plan) {
        var out = new ArrayList<TaxCalculations.Quote>();
        var province = plan.address().province();
        var postal = plan.address().postal();
        for (var l : plan.lines()) {
            out.add(taxes.calculate(
                    new TaxCalculations.Request(l.merchantId(), EscrowKind.GOODS, province, postal, l.lineCents())));
        }
        if (plan.option().feeCents() > 0) {
            out.add(taxes.calculate(new TaxCalculations.Request(
                    PaymentAuthorizations.PLATFORM,
                    EscrowKind.GOODS,
                    province,
                    postal,
                    plan.option().feeCents())));
        }
        return out;
    }

    /** "GST 5% · $2.47": tax lines added up per tax and rate. */
    private static List<TaxLine> summarize(List<TaxCalculations.Quote> quotes) {
        var sums = new LinkedHashMap<String, long[]>();
        var rates = new LinkedHashMap<String, BigDecimal>();
        for (var q : quotes) {
            for (var line : q.lines()) {
                var key = line.taxType() + "|"
                        + line.percent().stripTrailingZeros().toPlainString();
                sums.computeIfAbsent(key, _ -> new long[1])[0] += line.taxCents();
                rates.putIfAbsent(key, line.percent().stripTrailingZeros());
            }
        }
        return sums.entrySet().stream()
                .map(e -> new TaxLine(e.getKey().split("\\|")[0], rates.get(e.getKey()), e.getValue()[0]))
                .toList();
    }

    private String stepUpNeeded(String userId) {
        return secondFactors.hasSecondFactor(userId) ? "required" : "enrol";
    }

    private record PlannedLine(
            String offerId,
            @Nullable String variantId,
            String productId,
            String merchantId,
            String name,
            @Nullable String option,
            int qty,
            long unitCents,
            long lineCents) {}

    private record Plan(
            List<PlannedLine> lines,
            long subtotal,
            Option option,
            DeliveryAddresses.Address address,
            String market,
            String substitution) {}
}
