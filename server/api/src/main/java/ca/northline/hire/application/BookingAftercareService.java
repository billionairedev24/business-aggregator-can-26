package ca.northline.hire.application;

import ca.northline.booking.api.BookingMoney;
import ca.northline.booking.api.CustomerBookings;
import ca.northline.booking.api.CustomerHistory;
import ca.northline.hire.application.BookingCheckout.Confirmation;
import ca.northline.hire.domain.LateCancellation;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.promotions.api.Promotions;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BookingAftercare}. Cancelling: the fee of the booking's own policy snapshot ({@link LateCancellation}) is
 * captured from the hold and every other hold released ({@link EscrowLifecycle#cancelBooking}), a promo code's use and
 * the points go back ({@code Promotions.release}), then the booking is cancelled ({@code booking.cancelled}). The
 * balance a customer pays by hand uses the same {@code booking_balance} escrow the automatic hold does
 * ({@link BalanceHoldService}).
 */
@Service
@RequiredArgsConstructor
class BookingAftercareService implements BookingAftercare {

    static final String NOT_CANCELLABLE = "The job has started, so it can't be cancelled. Report a problem instead.";
    static final String NO_BALANCE = "There is no balance to pay for this booking.";
    static final String NOT_STARTED = "Start the payment first.";
    static final Set<String> CANCELLABLE = Set.of("requested", "confirmed", "en_route");

    private final BookingMoney money;
    private final CustomerBookings bookings;
    private final CustomerHistory history;
    private final BookingCheckout.ViewBooking views;
    private final EscrowLifecycle escrow;
    private final PaymentAuthorizations payments;
    private final PaymentSettings paymentSettings;
    private final PaymentGate gate;
    private final Promotions promotions;
    private final PublicProviders providers;
    private final PersonDirectory people;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CancelTerms cancellation(String customerId, String bookingId) {
        var f = own(customerId, bookingId);
        var fee = fee(f);
        var held = f.paid() ? f.depositCents() + depositTax(f) : 0;
        var balance = "held".equals(f.balance().state())
                ? f.balance().cents() + f.balance().taxCents()
                : 0;
        return new CancelTerms(
                CANCELLABLE.contains(f.state()),
                fee.late(),
                fee.feeCents(),
                fee.feeTaxCents(),
                fee.totalCents(),
                Math.max(0, held + balance - fee.totalCents()),
                (f.cancelFeeBps() == null || f.cancelFeeBps() == 0) && f.cancelFeeFixedCents() == 0
                        ? null
                        : f.freeCancelUntil(),
                f.cancelWindowHours() == null ? 0 : f.cancelWindowHours(),
                f.cancelFeeBps() == null ? 0 : f.cancelFeeBps(),
                f.cancelFeeFixedCents());
    }

    @Override
    @Transactional
    public Confirmation cancel(String customerId, String bookingId) {
        var f = own(customerId, bookingId);
        if (!"cancelled".equals(f.state())) {
            if (!CANCELLABLE.contains(f.state())) {
                throw new Conflict("job_state", NOT_CANCELLABLE);
            }
            var fee = fee(f);
            var now = clock.instant();
            var taken = escrow.cancelBooking(
                    bookingId, fee.feeCents(), fee.feeTaxCents(), "Late cancellation · " + f.title(), now);
            promotions.release(QuoteFlowService.SERVICE, bookingId);
            money.cancel(customerId, bookingId, taken.feeCents(), taken.feeTaxCents(), now);
        }
        return views.booking(customerId, bookingId);
    }

    @Override
    @Transactional
    public BalancePayment payBalance(
            String customerId,
            String bookingId,
            @Nullable String clientKey,
            boolean mfa,
            @Nullable String stepUpProof,
            @Nullable String savedCard) {
        var f = own(customerId, bookingId);
        var balance = f.balance();
        var payable = "pending".equals(balance.state()) || "failed".equals(balance.state());
        if (!payable || !"confirmed".equals(f.state())) {
            throw new Conflict("no_balance", NO_BALANCE);
        }
        gate.require(customerId, mfa, stepUpProof);
        var started = payments.start(
                new PaymentAuthorizations.Request(
                        f.merchantId(),
                        BalanceHoldService.BALANCE,
                        bookingId,
                        customerId,
                        balance.cents(),
                        balance.taxCents(),
                        "booking:" + bookingId,
                        clientKey),
                savedCard);
        return new BalancePayment(
                bookingId,
                balance.cents(),
                balance.taxCents(),
                balance.cents() + balance.taxCents(),
                started.status(),
                started.paymentIntent(),
                started.clientSecret(),
                paymentSettings.provider(),
                paymentSettings.publishableKey());
    }

    @Override
    @Transactional
    public Confirmation confirmBalance(String customerId, String bookingId) {
        var f = own(customerId, bookingId);
        if (!"held".equals(f.balance().state())) {
            var intent = payments.latest(BalanceHoldService.BALANCE, bookingId)
                    .orElseThrow(() -> new Conflict("not_started", NOT_STARTED));
            var customer = people.people(List.of(customerId)).get(customerId);
            var escrowId = escrow.hold(new EscrowLifecycle.Hold(
                    f.merchantId(),
                    EscrowKind.SERVICE,
                    BalanceHoldService.BALANCE,
                    bookingId,
                    f.balance().cents(),
                    f.balance().taxCents(),
                    customerId,
                    customer == null ? "Customer" : customer.shortName(),
                    f.title() + " · balance",
                    null,
                    null,
                    null,
                    "search",
                    intent,
                    clock.instant()));
            money.balanceHeld(bookingId, escrowId, clock.instant());
        }
        return views.booking(customerId, bookingId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingRow> bookings(String customerId) {
        var all = money.mine(customerId);
        var names = names(all.stream().map(BookingMoney.Facts::merchantId).toList());
        return all.stream()
                .map(f -> {
                    var provider = names.get(f.merchantId());
                    var held = !f.paid() || "cancelled".equals(f.state())
                            ? 0
                            : f.depositCents()
                                    + depositTax(f)
                                    + ("held".equals(f.balance().state())
                                            ? f.balance().cents() + f.balance().taxCents()
                                            : 0);
                    return new BookingRow(
                            f.id(),
                            f.ref(),
                            f.title(),
                            provider == null ? "" : provider.displayName(),
                            provider == null ? "" : provider.slug(),
                            f.startsAt(),
                            f.state(),
                            f.priceCents(),
                            held,
                            f.balance().state(),
                            f.balance().cents() + f.balance().taxCents(),
                            f.balance().dueAt(),
                            f.freeCancelUntil(),
                            f.cancellation() == null
                                    ? 0
                                    : f.cancellation().feeCents()
                                            + f.cancellation().feeTaxCents(),
                            f.quoteId(),
                            "/bookings/" + f.id());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RequestRow> requests(String customerId) {
        return history.requests(customerId, 200).stream()
                .map(r -> new RequestRow(
                        r.id(),
                        r.ref(),
                        r.title(),
                        r.createdAt(),
                        r.expiresAt(),
                        r.providers(),
                        r.quotes(),
                        r.lowestCents(),
                        r.status(),
                        r.bookingId(),
                        r.photoCount(),
                        r.bookingId() != null ? "/bookings/" + r.bookingId() : "/quotes/requests/" + r.id()))
                .toList();
    }

    private BookingMoney.Facts own(String customerId, String bookingId) {
        if (bookings.find(customerId, bookingId).isEmpty()) {
            throw new NotFound("booking", bookingId);
        }
        return money.facts(customerId, bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
    }

    private LateCancellation fee(BookingMoney.Facts f) {
        return LateCancellation.of(
                f.cancelFeeBps(),
                f.cancelFeeFixedCents(),
                f.freeCancelUntil(),
                f.priceCents(),
                f.taxCents(),
                f.depositCents(),
                f.paid(),
                clock.instant());
    }

    /** The tax on what the booking's hold covers: the booking's own rate. */
    private static long depositTax(BookingMoney.Facts f) {
        return f.priceCents() <= 0 ? 0 : Math.round((double) f.depositCents() * f.taxCents() / f.priceCents());
    }

    private Map<String, PublicProviders.Provider> names(List<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return providers.published(merchantIds.stream().distinct().toList(), "en").stream()
                .collect(Collectors.toMap(PublicProviders.Provider::merchantId, Function.identity()));
    }
}
