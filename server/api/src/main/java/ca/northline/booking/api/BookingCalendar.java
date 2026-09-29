package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Jobs that occupy a member's time (slot preview, time-off conflicts). Cancelled jobs are excluded. */
public interface BookingCalendar {

    /** Jobs overlapping [from, to) for one member, or for the whole team when {@code memberUserId} is null. */
    List<Busy> busy(String merchantId, @Nullable String memberUserId, Instant from, Instant to);

    record Busy(String bookingId, @Nullable String memberUserId, Instant startsAt, Instant endsAt) {}
}
