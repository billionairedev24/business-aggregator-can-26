package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-153: what the Services journey ({@code hire}) needs to move a booking's money — the balance of a deposit quote
 * (held before the event) and the customer's cancellation (with the late fee of the policy it was booked under). The
 * money itself is {@code payments.api.EscrowLifecycle}'s; this is the booking's side of it.
 */
public interface BookingMoney {

    /**
     * @param state {@code none | pending | held | failed | cancelled}
     * @param cents / {@code taxCents} what the balance holds, before tax and its tax
     * @param dueAt when it is (or was) held: the start less the configured lead
     * @param nextAt the next attempt while pending
     * @param failure the last refusal ({@code declined}, {@code authentication_required}, {@code no_saved_card})
     */
    record Balance(
            String state,
            long cents,
            long taxCents,
            @Nullable Instant dueAt,
            @Nullable Instant nextAt,
            int attempts,
            @Nullable String failure) {}

    /** @param byCustomer the customer cancelled (never the business, today) */
    record Cancellation(Instant at, long feeCents, long feeTaxCents, boolean byCustomer) {}

    /**
     * A customer's booking with its money facts.
     *
     * @param depositCents what the booking's hold covers before tax (the deposit of a deposit quote)
     * @param cancelWindowHours / {@code cancelFeeBps} / {@code cancelFeeFixedCents}: the late-cancellation terms shown
     *     when it was booked (null before S-153: free)
     */
    record Facts(
            String id,
            String ref,
            String merchantId,
            String customerId,
            String state,
            String title,
            Instant startsAt,
            long priceCents,
            long depositCents,
            long taxCents,
            boolean paid,
            @Nullable String quoteId,
            @Nullable Integer cancelWindowHours,
            @Nullable Integer cancelFeeBps,
            long cancelFeeFixedCents,
            @Nullable Instant freeCancelUntil,
            Balance balance,
            @Nullable Cancellation cancellation) {}

    /** The customer's own booking; empty for anyone else's. */
    Optional<Facts> facts(String customerId, String bookingId);

    /**
     * The late-cancellation terms the customer was shown, kept with the booking (the provider changing them later
     * doesn't change this booking's).
     *
     * @param feeFixedCents a fixed fee, already capped at {@code maxFeeBps} of the booking's price (0 = a share)
     */
    void terms(String bookingId, int windowHours, int feeBps, long feeFixedCents, Instant freeCancelUntil);

    /** The accepted deposit quote still owes {@code cents} + {@code taxCents}, to hold at {@code dueAt}. */
    void scheduleBalance(String bookingId, long cents, long taxCents, Instant dueAt);

    /** A balance whose next attempt is due. */
    record DueBalance(
            String bookingId,
            String ref,
            String merchantId,
            String customerId,
            String title,
            long cents,
            long taxCents,
            int attempts,
            Instant startsAt) {}

    /** Pending balances of confirmed bookings whose next attempt is due, oldest first. */
    List<DueBalance> dueBalances(Instant now, int limit);

    /** The balance is held ({@code booking.balance_held}). */
    void balanceHeld(String bookingId, String escrowId, Instant at);

    /**
     * Holding the balance was refused ({@code booking.balance_failed}): the customer is asked to pay it from the
     * booking, the provider is told. {@code retryAt} null = the last attempt: the balance stays {@code failed}.
     */
    void balanceFailed(String bookingId, String reason, @Nullable Instant retryAt, Instant at);

    /** The customer cancels: the booking becomes {@code cancelled} ({@code booking.cancelled}). */
    Facts cancel(String customerId, String bookingId, long feeCents, long feeTaxCents, Instant at);

    /** The customer's bookings, newest start first, at most 200 (Account › Bookings). */
    List<Facts> mine(String customerId);
}
