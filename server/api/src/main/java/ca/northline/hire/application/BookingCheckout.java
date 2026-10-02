package ca.northline.hire.application;

import ca.northline.hire.domain.BookingRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The booking wizard (S-55, design 06 {@code book}): job details → location &amp; access → schedule → payment →
 * confirmed. The schedule step shows the provider's live calendar; continuing to payment holds the slot for 10 minutes;
 * payment opens a manual-capture PaymentIntent (escrow, S-11) that Stripe.js confirms with the card; confirming records
 * the escrow hold and writes the booking ({@code booking.confirmed}).
 */
public final class BookingCheckout {
    private BookingCheckout() {}

    public interface ViewCalendar {
        /** @param from the first day, null = today in the market's time zone */
        Calendar calendar(
                String slug, String serviceId, @Nullable LocalDate from, int days, @Nullable String customerId);
    }

    public interface HoldSlot {
        HoldView hold(String customerId, String slug, String serviceId, Instant startsAt, @Nullable BigDecimal hours);
    }

    public interface ReleaseSlot {
        void release(String customerId, String holdId);
    }

    /** Prices the booking and opens the payment for it — or, for a free consultation, books at once. */
    public interface StartCheckout {
        /**
         * @param mfa the session has {@code acr=mfa}; otherwise a paid booking needs {@code stepUpProof}
         *     ({@link SecondFactorRequired})
         */
        Checkout start(
                String customerId,
                BookingRequest request,
                @Nullable String clientKey,
                boolean mfa,
                @Nullable String stepUpProof);
    }

    /** The card was authorized (Stripe.js): record the escrow hold and write the booking. */
    public interface ConfirmBooking {
        Confirmation confirm(String customerId, String holdId);
    }

    public interface ViewBooking {
        Confirmation booking(String customerId, String bookingId);
    }

    /**
     * "Release payment" (consumer app, design 01 {@code signoff}): the customer signs the completed job off and the
     * escrow releases at once. Signing off again answers the same.
     */
    public interface SignOffBooking {
        Confirmation signOff(String customerId, String bookingId);
    }

    /** @param timeZone the business's time zone (its province's, from the region model): days and times are read in it */
    public record Calendar(String serviceId, int durationMin, List<Day> days, String timeZone) {}

    public record Day(LocalDate date, @Nullable String closed, int free, List<Slot> slots) {}

    public record Slot(Instant startsAt, boolean free) {}

    public record HoldView(String holdId, String bookingId, Instant startsAt, Instant endsAt, Instant expiresAt) {}

    /**
     * @param status {@code requires_action} (Stripe.js confirms the card with {@code clientSecret}), {@code authorized}
     *     (the fake gateway, or a saved card that needed nothing), or {@code confirmed} (nothing to pay: booked)
     * @param provider {@code stripe} (Stripe.js with {@code publishableKey}) or {@code fake} (local: nothing to confirm)
     */
    public record Checkout(
            String holdId,
            String bookingId,
            long priceCents,
            long taxCents,
            long totalCents,
            String status,
            @Nullable String paymentIntent,
            @Nullable String clientSecret,
            String provider,
            @Nullable String publishableKey,
            @Nullable Confirmation booking) {}

    /**
     * The booked job as the confirmation shows it, and how it went so far (consumer app S-100: day-of ETA, sign-off).
     *
     * @param state {@code confirmed | en_route | on_site | completed | signed_off | disputed | cancelled}
     * @param timeZone the business's time zone: the booking's times are shown in it
     * @param steps the job-flow steps so far, oldest first
     * @param report the member's written report at completion
     * @param photoCount completion photos the member took
     * @param releasesAt when the escrow releases by itself (48 h after completion) while the customer hasn't signed off
     */
    public record Confirmation(
            String bookingId,
            String ref,
            String providerName,
            String providerSlug,
            @Nullable String memberFirstName,
            String title,
            String type,
            Instant startsAt,
            Instant endsAt,
            @Nullable String addressLine,
            long priceCents,
            long taxCents,
            long heldCents,
            @Nullable Instant freeCancelUntil,
            String merchantId,
            String state,
            String timeZone,
            List<Step> steps,
            @Nullable String report,
            int photoCount,
            @Nullable Instant releasesAt) {}

    /** @param type {@code en_route | on_site | completed | signed_off} */
    public record Step(String type, Instant at) {}
}
