package ca.northline.booking.api;

import java.time.Instant;
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
            @Nullable Instant freeCancelUntil) {
        public NewBooking {
            details = Map.copyOf(details);
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
     * {@code bookingId} (a retry returns the booking already written).
     */
    CustomerBooking book(NewBooking booking);

    /** The customer's own booking; empty for anyone else's. */
    Optional<CustomerBooking> find(String customerId, String bookingId);
}
