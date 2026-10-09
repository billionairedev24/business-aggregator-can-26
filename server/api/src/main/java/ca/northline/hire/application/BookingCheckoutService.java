package ca.northline.hire.application;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.availability.api.SlotHolds;
import ca.northline.booking.api.CustomerBookings;
import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.catalogue.api.ServiceOffers.Offer;
import ca.northline.hire.application.BookingCheckout.Calendar;
import ca.northline.hire.application.BookingCheckout.Checkout;
import ca.northline.hire.application.BookingCheckout.ConfirmBooking;
import ca.northline.hire.application.BookingCheckout.Confirmation;
import ca.northline.hire.application.BookingCheckout.Day;
import ca.northline.hire.application.BookingCheckout.HoldSlot;
import ca.northline.hire.application.BookingCheckout.HoldView;
import ca.northline.hire.application.BookingCheckout.ReleaseSlot;
import ca.northline.hire.application.BookingCheckout.SignOffBooking;
import ca.northline.hire.application.BookingCheckout.Slot;
import ca.northline.hire.application.BookingCheckout.StartCheckout;
import ca.northline.hire.application.BookingCheckout.Step;
import ca.northline.hire.application.BookingCheckout.ViewBooking;
import ca.northline.hire.application.BookingCheckout.ViewCalendar;
import ca.northline.hire.domain.BookingRequest;
import ca.northline.hire.domain.Pricing;
import ca.northline.hire.domain.ServiceKind;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.promotions.api.Promotions;
import ca.northline.region.api.TaxRates;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.crypto.SecretSealer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The booking wizard's server side (S-55). A slot hold (10 min, Valkey) carries the booking id chosen before payment and,
 * sealed, what the customer entered; the PaymentIntent refers to that booking; confirming checks the authorization at
 * Stripe ({@link EscrowLifecycle#hold}), writes the booking ({@link CustomerBookings#book}, {@code booking.confirmed})
 * and frees the hold.
 */
@Service
@RequiredArgsConstructor
class BookingCheckoutService
        implements ViewCalendar,
                HoldSlot,
                ReleaseSlot,
                StartCheckout,
                ConfirmBooking,
                ViewBooking,
                SignOffBooking,
                BookingCheckout.PriceBooking {

    /** Design 06: "free cancellation until 12 h before". */
    static final Duration FREE_CANCEL = Duration.ofHours(12);

    static final String QUOTED = "This service is priced by quote — ask for a quote instead.";
    static final String NOT_INSTANT = "This service needs the provider's approval — ask for a quote instead.";
    static final String HOLD_GONE = "Your 10-minute hold ended. Pick the time again.";
    static final int MAX_DAYS = 14;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PublicProviders providers;
    private final ServiceOffers offers;
    private final ProviderSlots slots;
    private final SlotHolds holds;
    private final PaymentAuthorizations payments;
    private final EscrowLifecycle escrow;
    private final PaymentSettings paymentSettings;
    private final PaymentGate gate;
    private final CustomerBookings bookings;
    private final PersonDirectory people;
    private final TaxRates taxRates;
    private final SecretSealer sealer;
    private final RegionDefaults region;
    private final Clock clock;
    private final Promotions promotions;

    /** What travels with the hold between "Hold $…" and the card confirmation (sealed: it has the access note). */
    record Draft(
            BookingRequest request,
            long priceCents,
            long taxCents,
            @Nullable String paymentIntent) {}

    @Override
    public Calendar calendar(
            String slug, String serviceId, @Nullable LocalDate from, int days, @Nullable String customerId) {
        var provider = provider(slug);
        var offer = offer(provider, serviceId);
        var zone = region.zone(provider.province());
        var today = LocalDate.now(clock.withZone(zone));
        var start = from == null || from.isBefore(today) ? today : from;
        var duration = offer.durationMin();
        return new Calendar(
                serviceId,
                duration,
                slots.days(provider.merchantId(), duration, start, Math.clamp(days, 1, MAX_DAYS), customerId).stream()
                        .map(d -> new Day(
                                d.date(),
                                d.closed(),
                                (int) d.free(),
                                d.slots().stream()
                                        .map(s -> new Slot(s.startsAt(), s.free()))
                                        .toList()))
                        .toList(),
                zone.getId());
    }

    @Override
    public HoldView hold(
            String customerId, String slug, String serviceId, Instant startsAt, @Nullable BigDecimal hours) {
        var provider = provider(slug);
        var offer = bookable(provider, serviceId);
        var duration = hours == null || !"hourly".equals(offer.pricingMode())
                ? offer.durationMin()
                : hours.multiply(BigDecimal.valueOf(60)).intValue();
        var hold = holds.hold(provider.merchantId(), serviceId, startsAt, duration, customerId);
        return new HoldView(hold.id(), hold.bookingId(), hold.startsAt(), hold.endsAt(), hold.expiresAt());
    }

    @Override
    public void release(String customerId, String holdId) {
        holds.find(holdId).filter(h -> h.customerId().equals(customerId)).ifPresent(h -> holds.release(h.id()));
    }

    @Override
    public Checkout start(
            String customerId,
            BookingRequest request,
            @Nullable String clientKey,
            boolean mfa,
            @Nullable String stepUpProof) {
        if (request.holdId() == null || request.serviceId() == null) {
            throw RuleViolation.of("holdId", "required", "Pick the time again.");
        }
        var hold = ownHold(customerId, request.holdId());
        if (!Objects.equals(hold.serviceId(), request.serviceId())) {
            throw RuleViolation.of("serviceId", "mismatch", "Pick the time again for this service.");
        }
        var offer = offers.find(request.serviceId(), "en")
                .filter(o -> o.merchantId().equals(hold.merchantId()))
                .orElseThrow(() -> new NotFound("service", request.serviceId()));
        var kind = kind(offer);
        var pricing = Pricing.of(
                kind,
                offer.pricingMode(),
                offer.priceCents(),
                request.hours(),
                taxRates.bpsFor(province(hold.merchantId())));
        request.validate(kind, vehicle(offer), pricing.free());
        if (pricing.free()) {
            var booked = book(hold, request, offer, kind, pricing.priceCents(), pricing.taxCents(), null, 0, 0);
            holds.release(hold.id());
            return new Checkout(
                    hold.id(),
                    hold.bookingId(),
                    0,
                    0,
                    0,
                    "confirmed",
                    null,
                    null,
                    paymentSettings.provider(),
                    null,
                    booked,
                    0,
                    0,
                    null);
        }
        gate.require(customerId, mfa, stepUpProof);
        var promo = promotions.reserve(
                hold.bookingId(),
                hold.expiresAt(),
                basket(customerId, hold.bookingId(), hold.merchantId(), pricing.priceCents()),
                new Promotions.Ask(request.promoCode(), request.spendPoints()));
        var line = promo.lines().getFirst();
        var taxable = line.taxableCents();
        var tax = Pricing.taxOn(taxable, taxRates.bpsFor(province(hold.merchantId())));
        var started = payments.start(new PaymentAuthorizations.Request(
                hold.merchantId(),
                "booking",
                hold.bookingId(),
                customerId,
                taxable,
                tax,
                "booking:" + hold.bookingId(),
                clientKey,
                null,
                0,
                line.pointsCents()));
        attach(hold.id(), new Draft(request, taxable, tax, started.paymentIntent()));
        return new Checkout(
                hold.id(),
                hold.bookingId(),
                pricing.priceCents(),
                tax,
                taxable + tax - line.pointsCents(),
                started.status(),
                started.paymentIntent(),
                started.clientSecret(),
                paymentSettings.provider(),
                paymentSettings.publishableKey(),
                null,
                promo.discountCents(),
                promo.pointsCents(),
                promo.code());
    }

    @Override
    public BookingCheckout.Price price(
            String customerId,
            String holdId,
            String serviceId,
            @Nullable BigDecimal hours,
            @Nullable String promoCode,
            boolean usePoints) {
        var hold = ownHold(customerId, holdId);
        var offer = offers.find(serviceId, "en")
                .filter(o -> o.merchantId().equals(hold.merchantId()))
                .orElseThrow(() -> new NotFound("service", serviceId));
        var bps = taxRates.bpsFor(province(hold.merchantId()));
        var pricing = Pricing.of(kind(offer), offer.pricingMode(), offer.priceCents(), hours, bps);
        if (pricing.free()) {
            return new BookingCheckout.Price(0, 0, 0, 0, 0, 0, 0, null);
        }
        var promo = promotions.price(
                basket(customerId, hold.bookingId(), hold.merchantId(), pricing.priceCents()),
                new Promotions.Ask(promoCode, usePoints));
        var line = promo.lines().getFirst();
        var tax = Pricing.taxOn(line.taxableCents(), bps);
        return new BookingCheckout.Price(
                pricing.priceCents(),
                promo.discountCents(),
                tax,
                promo.points(),
                promo.pointsCents(),
                promo.pointsAvailable(),
                line.taxableCents() + tax - promo.pointsCents(),
                promo.code());
    }

    private static Promotions.Basket basket(String customerId, String bookingId, String merchantId, long cents) {
        return new Promotions.Basket(
                customerId, "service", List.of(new Promotions.Item("booking", bookingId, merchantId, cents)));
    }

    @Override
    @Transactional
    public Confirmation confirm(String customerId, String holdId) {
        var hold = ownHold(customerId, holdId);
        var draft =
                draft(hold.id()).orElseThrow(() -> new Conflict("checkout_not_started", "Start the payment first."));
        var intent = draft.paymentIntent();
        if (intent == null) {
            throw new Conflict("checkout_not_started", "Start the payment first.");
        }
        var offer = offers.find(Objects.requireNonNull(hold.serviceId()), "en")
                .orElseThrow(() -> new NotFound("service", String.valueOf(hold.serviceId())));
        var kind = kind(offer);
        var customer = people.people(List.of(customerId)).get(customerId);
        var promo = promotions.reserved("service", hold.bookingId()).line("booking", hold.bookingId());
        var discount = promo.map(l -> new EscrowLifecycle.Discount(l.discountCents(), l.fundedBy(), l.pointsCents()))
                .orElse(null);
        var escrowId = escrow.hold(new EscrowLifecycle.Hold(
                hold.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                hold.bookingId(),
                draft.priceCents(),
                draft.taxCents(),
                customerId,
                customer == null ? "Customer" : customer.shortName(),
                offer.name(),
                null,
                offer.serviceId(),
                offer.name(),
                "search",
                intent,
                clock.instant(),
                null,
                discount));
        promotions.redeem("service", hold.bookingId());
        var booked = book(
                hold,
                draft.request(),
                offer,
                kind,
                draft.priceCents(),
                draft.taxCents(),
                escrowId,
                discount == null ? 0 : discount.codeCents(),
                discount == null ? 0 : discount.pointsCents());
        holds.release(hold.id());
        return booked;
    }

    @Override
    public Confirmation booking(String customerId, String bookingId) {
        var b = bookings.find(customerId, bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        return confirmation(customerId, b);
    }

    @Override
    public Confirmation signOff(String customerId, String bookingId) {
        return confirmation(customerId, bookings.signOff(customerId, bookingId));
    }

    private Confirmation book(
            SlotHolds.Hold hold,
            BookingRequest request,
            Offer offer,
            ServiceKind kind,
            long priceCents,
            long taxCents,
            @Nullable String escrowId,
            long discountCents,
            long pointsCents) {
        var line = trimmed(request.addressLine());
        var unit = trimmed(request.unit());
        var address = line == null ? null : unit == null ? line : line + ", " + unit;
        var booked = bookings.book(new CustomerBookings.NewBooking(
                hold.bookingId(),
                hold.merchantId(),
                hold.memberUserId(),
                hold.customerId(),
                offer.serviceId(),
                null,
                kind.code(),
                offer.name(),
                hold.startsAt(),
                hold.endsAt(),
                address,
                request.area(),
                request.details(),
                trimmed(request.accessNote()),
                trimmed(request.contactPhone()),
                priceCents,
                priceCents,
                taxCents,
                escrowId,
                priceCents == 0 ? null : hold.startsAt().minus(FREE_CANCEL),
                discountCents,
                pointsCents,
                request.siteLat(),
                request.siteLng()));
        return confirmation(hold.customerId(), booked);
    }

    private Confirmation confirmation(String customerId, CustomerBookings.CustomerBooking b) {
        var provider =
                providers.published(List.of(b.merchantId()), "en").stream().findFirst();
        var memberId = b.memberUserId();
        var member = memberId == null ? null : people.people(List.of(memberId)).get(memberId);
        var progress = bookings.progress(customerId, b.id())
                .orElseGet(() -> new CustomerBookings.Progress(List.of(), null, 0));
        var completedAt = progress.steps().stream()
                .filter(s -> "completed".equals(s.type()))
                .map(CustomerBookings.Step::at)
                .reduce((first, last) -> last);
        var releasesAt = b.paid() && "completed".equals(b.state())
                ? completedAt.map(EscrowKind.SERVICE::releaseAt).orElse(null)
                : null;
        return new Confirmation(
                b.id(),
                b.ref(),
                provider.map(PublicProviders.Provider::displayName).orElse(""),
                provider.map(PublicProviders.Provider::slug).orElse(""),
                member == null ? null : member.displayName().strip().split("\\s+")[0],
                b.title(),
                b.type(),
                b.startsAt(),
                b.endsAt(),
                b.addressLine(),
                b.priceCents(),
                b.taxCents(),
                b.paid() ? b.depositCents() + b.taxCents() : 0,
                b.freeCancelUntil(),
                b.merchantId(),
                b.state(),
                region.zone(province(b.merchantId())).getId(),
                progress.steps().stream().map(s -> new Step(s.type(), s.at())).toList(),
                progress.report(),
                progress.photoCount(),
                releasesAt);
    }

    private SlotHolds.Hold ownHold(String customerId, String holdId) {
        return holds.find(holdId)
                .filter(h -> h.customerId().equals(customerId))
                .orElseThrow(() -> new Conflict("hold_expired", HOLD_GONE));
    }

    private void attach(String holdId, Draft draft) {
        var box = sealer.seal(JSON.writeValueAsString(draft), "slot-hold:" + holdId);
        holds.attach(holdId, JSON.writeValueAsString(box));
    }

    private java.util.Optional<Draft> draft(String holdId) {
        return holds.checkout(holdId).map(json -> {
            var box = JSON.readValue(json, SecretSealer.Sealed.class);
            return JSON.readValue(sealer.open(box, "slot-hold:" + holdId), Draft.class);
        });
    }

    /** The business's own province (onboarding), else the configured default: its bookings are taxed there. */
    private String province(String merchantId) {
        return providers.published(List.of(merchantId), "en").stream()
                .map(PublicProviders.Provider::province)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(region.province(null));
    }

    private PublicProviders.Provider provider(String slug) {
        return providers.bySlug(slug, "en").orElseThrow(() -> new NotFound("provider", slug));
    }

    private Offer offer(PublicProviders.Provider provider, String serviceId) {
        return offers.find(serviceId, "en")
                .filter(o -> o.merchantId().equals(provider.merchantId()))
                .orElseThrow(() -> new NotFound("service", serviceId));
    }

    /** Quote-only services and those without instant book go through quotes (S-56). */
    private Offer bookable(PublicProviders.Provider provider, String serviceId) {
        var offer = offer(provider, serviceId);
        var kind = kind(offer);
        if (offer.quoteOnly() && kind != ServiceKind.CONSULT) {
            throw RuleViolation.of("serviceId", "quote", QUOTED);
        }
        if (!offer.instantBook() && kind != ServiceKind.CONSULT) {
            throw RuleViolation.of("serviceId", "approval", NOT_INSTANT);
        }
        return offer;
    }

    private static ServiceKind kind(Offer offer) {
        var category = offer.categoryId();
        return category == null ? ServiceKind.VISIT : ServiceKind.of(category, null);
    }

    private static boolean vehicle(Offer offer) {
        var category = offer.categoryId();
        return category != null && ServiceKind.vehicle(category);
    }

    private static @Nullable String trimmed(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
