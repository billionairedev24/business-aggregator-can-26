package ca.northline.booking.application;

import ca.northline.booking.api.BookingBalanceFailed;
import ca.northline.booking.api.BookingBalanceHeld;
import ca.northline.booking.api.BookingCancelled;
import ca.northline.booking.api.BookingMoney;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BookingMoney}: the booking's side of its balance and its cancellation — the state on {@code booking.bookings}
 * and the events the team and the customer are told by. The cancellation goes through the {@code Booking} aggregate
 * (who may cancel, from which states) and is logged like every transition.
 */
@Service
@RequiredArgsConstructor
@Transactional
class BookingMoneyService implements BookingMoney {

    private final BookingMoneyStore store;
    private final BookingRepository bookings;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public Optional<Facts> facts(String customerId, String bookingId) {
        return store.facts(bookingId).filter(f -> f.customerId().equals(customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Facts> mine(String customerId) {
        return store.ofCustomer(customerId, 200);
    }

    @Override
    public void terms(String bookingId, int windowHours, int feeBps, long feeFixedCents, Instant freeCancelUntil) {
        store.policy(bookingId, windowHours, feeBps, feeFixedCents, freeCancelUntil);
    }

    @Override
    public void scheduleBalance(String bookingId, long cents, long taxCents, Instant dueAt) {
        if (cents + taxCents > 0) {
            store.scheduleBalance(bookingId, cents, taxCents, dueAt);
        }
    }

    @Override
    public List<DueBalance> dueBalances(Instant now, int limit) {
        return store.dueBalances(now, limit);
    }

    @Override
    public void balanceHeld(String bookingId, String escrowId, Instant at) {
        var f = store.facts(bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        if ("held".equals(f.balance().state())) {
            return;
        }
        store.balanceHeld(bookingId, escrowId, at);
        events.publishEvent(new BookingBalanceHeld(
                Ids.next(),
                at,
                bookingId,
                f.merchantId(),
                f.customerId(),
                f.balance().cents(),
                f.balance().taxCents(),
                f.startsAt()));
    }

    @Override
    public void balanceFailed(String bookingId, String reason, @Nullable Instant retryAt, Instant at) {
        var f = store.facts(bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        store.balanceFailed(bookingId, reason, retryAt);
        events.publishEvent(new BookingBalanceFailed(
                Ids.next(),
                at,
                bookingId,
                f.merchantId(),
                f.customerId(),
                f.balance().cents(),
                f.balance().taxCents(),
                f.startsAt(),
                f.balance().attempts() + 1,
                reason,
                retryAt));
    }

    @Override
    public Facts cancel(String customerId, String bookingId, long feeCents, long feeTaxCents, Instant at) {
        var facts = facts(customerId, bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        var booking =
                bookings.find(facts.merchantId(), bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        var entry = booking.cancelByCustomer(customerId, at);
        if (entry == null) {
            return facts;
        }
        bookings.save(booking);
        bookings.append(List.of(entry));
        store.cancelled(bookingId, customerId, feeCents, feeTaxCents, at);
        events.publishEvent(new BookingCancelled(
                Ids.next(),
                at,
                bookingId,
                facts.merchantId(),
                customerId,
                booking.getMemberUserId(),
                facts.startsAt(),
                feeCents + feeTaxCents > 0,
                feeCents,
                feeTaxCents));
        return Objects.requireNonNull(store.facts(bookingId).orElse(facts));
    }
}
