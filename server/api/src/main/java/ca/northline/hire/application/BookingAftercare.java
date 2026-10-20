package ca.northline.hire.application;

import ca.northline.hire.application.BookingCheckout.Confirmation;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-153: what a customer does with a booking after making it — see what cancelling costs and cancel (the late fee is
 * taken from the hold, the rest released), pay a deposit quote's balance when it couldn't be held automatically — and
 * the account's lists of bookings and quote requests.
 */
public interface BookingAftercare {

    /**
     * What cancelling now would cost.
     *
     * @param late inside the policy's window: {@code feeTotalCents} is kept from the hold
     * @param releasedCents what goes back to the card (an estimate: points and codes are returned by their own rules)
     * @param freeUntil until when it is free (null: always free)
     */
    record CancelTerms(
            boolean cancellable,
            boolean late,
            long feeCents,
            long feeTaxCents,
            long feeTotalCents,
            long releasedCents,
            @Nullable Instant freeUntil,
            int windowHours,
            int feeBps,
            long feeFixedCents) {}

    CancelTerms cancellation(String customerId, String bookingId);

    /** Cancels (idempotent): 409 {@code job_state} once the provider is on site. */
    Confirmation cancel(String customerId, String bookingId);

    /**
     * Pays a deposit quote's balance from the booking page (a pending or failed balance): a manual-capture
     * PaymentIntent, optionally with a saved card; Stripe.js confirms it with {@code clientSecret} when needed.
     */
    BalancePayment payBalance(
            String customerId,
            String bookingId,
            @Nullable String clientKey,
            boolean mfa,
            @Nullable String stepUpProof,
            @Nullable String savedCard);

    record BalancePayment(
            String bookingId,
            long amountCents,
            long taxCents,
            long totalCents,
            String status,
            @Nullable String paymentIntent,
            @Nullable String clientSecret,
            String provider,
            @Nullable String publishableKey) {}

    /** The balance's card is authorized: record the escrow hold. */
    Confirmation confirmBalance(String customerId, String bookingId);

    /**
     * One of the customer's bookings (Account › Bookings).
     *
     * @param state the booking's ({@code confirmed … signed_off | cancelled})
     * @param heldCents what is held for it now (the deposit, and the balance once held)
     * @param balanceState {@code none | pending | held | failed | cancelled}
     */
    record BookingRow(
            String id,
            String ref,
            String title,
            String providerName,
            String providerSlug,
            Instant startsAt,
            String state,
            long priceCents,
            long heldCents,
            String balanceState,
            long balanceCents,
            @Nullable Instant balanceDueAt,
            @Nullable Instant freeCancelUntil,
            long cancelFeeCents,
            @Nullable String quoteId,
            String href) {}

    List<BookingRow> bookings(String customerId);

    /** One of the customer's quote requests (Account › Quote requests). */
    record RequestRow(
            String id,
            String ref,
            String title,
            Instant createdAt,
            @Nullable Instant expiresAt,
            int providers,
            int quotes,
            @Nullable Long lowestCents,
            String status,
            @Nullable String bookingId,
            int photoCount,
            String href) {}

    List<RequestRow> requests(String customerId);
}
