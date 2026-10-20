package ca.northline.booking.application;

import ca.northline.booking.api.BookingMoney.DueBalance;
import ca.northline.booking.api.BookingMoney.Facts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-153): the balance and cancellation columns of {@code booking.bookings} (V407). */
public interface BookingMoneyStore {

    Optional<Facts> facts(String bookingId);

    /** The customer's bookings, newest start first. */
    List<Facts> ofCustomer(String customerId, int limit);

    void scheduleBalance(String bookingId, long cents, long taxCents, Instant dueAt);

    List<DueBalance> dueBalances(Instant now, int limit);

    void balanceHeld(String bookingId, String escrowId, Instant at);

    /** One more failed attempt; {@code retryAt} null = the balance is {@code failed} for good. */
    void balanceFailed(String bookingId, String reason, @Nullable Instant retryAt);

    /** The cancellation: who, when, the fee; a pending or held balance becomes {@code cancelled}. */
    void cancelled(String bookingId, String byUserId, long feeCents, long feeTaxCents, Instant at);

    /** The policy snapshot taken when the booking was written (S-153). */
    void policy(String bookingId, int windowHours, int feeBps, long feeFixedCents, Instant freeCancelUntil);
}
