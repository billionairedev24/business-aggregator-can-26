package ca.northline.booking.application;

import ca.northline.booking.api.CustomerBookings.CustomerBooking;
import ca.northline.booking.api.CustomerBookings.NewBooking;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.time.Instant;
import java.util.Optional;

/** Outbound port: bookings customers make (S-55) and their sealed access notes ({@code booking.access_notes}). */
public interface CustomerBookingStore {

    /**
     * Serialises bookings of one member (transaction-scoped advisory lock) and tells whether a job that isn't cancelled
     * overlaps [from, to).
     */
    boolean lockAndCheckOverlap(String memberUserId, Instant from, Instant to);

    /** Inserts the booking, confirmed, with a new {@code BK-} reference; returns the reference. */
    String insert(NewBooking booking, Instant at);

    void sealAccess(String bookingId, Sealed note);

    Optional<Sealed> accessNote(String bookingId);

    Optional<CustomerBooking> find(String bookingId);

    Optional<String> customerOf(String bookingId);
}
