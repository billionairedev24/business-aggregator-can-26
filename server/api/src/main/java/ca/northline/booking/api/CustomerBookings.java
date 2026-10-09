package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Bookings customers make themselves (S-55 wizard, S-56 accepted quotes), called by the Services journey once the
 * money is held: the booking is written confirmed, its private access instructions sealed, and {@code booking.confirmed}
 * published in the same transaction.
 */
public interface CustomerBookings {

    /**
     * @param bookingId chosen before payment (the PaymentIntent's reference and transfer group)
     * @param type {@code visit | home | event | appointment | consult}
     * @param title the service or job ("Brake inspection"), kept as it was booked
     * @param details what the customer told the provider (vehicle, home, event, urgency…): no access codes or phone
     * @param accessNote gate code, buzzer, keys — sealed, shown to the provider only around the visit
     * @param contactPhone the number for the day — sealed with the access note
     * @param escrowId the payments escrow holding the money; null when nothing is paid (a free consultation)
     */
    record NewBooking(
            String bookingId,
            String merchantId,
            String memberUserId,
            String customerId,
            @Nullable String serviceId,
            @Nullable String quoteId,
            String type,
            String title,
            Instant startsAt,
            Instant endsAt,
            @Nullable String addressLine,
            @Nullable String area,
            Map<String, Object> details,
            @Nullable String accessNote,
            @Nullable String contactPhone,
            long priceCents,
            long depositCents,
            long taxCents,
            @Nullable String escrowId,
            @Nullable Instant freeCancelUntil,
            long discountCents,
            long pointsCents,
            @Nullable Double siteLat,
            @Nullable Double siteLng) {
        public NewBooking {
            details = Map.copyOf(details);
        }

        /** Without a promo code, points or a located job site (before mobile gaps part 2). */
        public NewBooking(
                String bookingId,
                String merchantId,
                String memberUserId,
                String customerId,
                @Nullable String serviceId,
                @Nullable String quoteId,
                String type,
                String title,
                Instant startsAt,
                Instant endsAt,
                @Nullable String addressLine,
                @Nullable String area,
                Map<String, Object> details,
                @Nullable String accessNote,
                @Nullable String contactPhone,
                long priceCents,
                long depositCents,
                long taxCents,
                @Nullable String escrowId,
                @Nullable Instant freeCancelUntil) {
            this(
                    bookingId,
                    merchantId,
                    memberUserId,
                    customerId,
                    serviceId,
                    quoteId,
                    type,
                    title,
                    startsAt,
                    endsAt,
                    addressLine,
                    area,
                    details,
                    accessNote,
                    contactPhone,
                    priceCents,
                    depositCents,
                    taxCents,
                    escrowId,
                    freeCancelUntil,
                    0,
                    0,
                    null,
                    null);
        }
    }

    /** What the customer sees of their booking (the confirmation). */
    record CustomerBooking(
            String id,
            String ref,
            String merchantId,
            @Nullable String memberUserId,
            String state,
            String title,
            String type,
            Instant startsAt,
            Instant endsAt,
            @Nullable String addressLine,
            long priceCents,
            long depositCents,
            long taxCents,
            boolean paid,
            @Nullable Instant freeCancelUntil) {}

    /**
     * Writes the booking unless the member already has a job then (409 {@code slot_taken}); idempotent on
     * {@code booking.bookingId()} (a retry returns the booking already written).
     */
    CustomerBooking book(NewBooking booking);

    /** The customer's own booking; empty for anyone else's. */
    Optional<CustomerBooking> find(String customerId, String bookingId);

    /**
     * How the customer's job went so far (consumer app S-100: the day-of ETA and the sign-off): the steps the member
     * recorded, the written report and how many completion photos there are. Empty for anyone else's booking.
     */
    Optional<Progress> progress(String customerId, String bookingId);

    /**
     * The customer signs the completed job off (consumer app S-100): the job moves to {@code signed_off} and
     * {@link BookingSignedOff} is published, which releases the escrow at once. Signing off again answers the same; a job
     * that isn't completed is a 409 {@code job_state}; someone else's booking is a 404.
     */
    CustomerBooking signOff(String customerId, String bookingId);

    /**
     * @param type {@code en_route | on_site | completed | signed_off}
     * @param at when the member (or the customer, for sign-off) recorded it
     */
    record Step(String type, Instant at) {}

    /**
     * @param steps oldest first; only the job-flow steps (no GPS, no approvals, no photos)
     * @param report the member's written report at completion
     * @param photoCount completion photos (the customer can't download them yet: MOBILE_PLAN § API gaps)
     */
    record Progress(List<Step> steps, @Nullable String report, int photoCount) {
        public Progress {
            steps = List.copyOf(steps);
        }
    }
}
