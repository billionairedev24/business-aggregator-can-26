package ca.northline.booking.application;

import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.Booking;
import ca.northline.booking.domain.BookingLogEntry;
import java.util.List;
import java.util.Optional;

/** Outbound port for the Booking aggregate and its append-only log. */
public interface BookingRepository {

    Optional<Booking> find(String merchantId, String bookingId);

    /** Saves the state; throws an optimistic-locking failure when someone else moved the job meanwhile. */
    void save(Booking booking);

    void append(List<BookingLogEntry> entries);

    void insert(Approval approval);
}
