package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Jobs that occupy a member's time (slot preview, time-off conflicts, calendar write-back). Requested and cancelled
 * jobs are excluded: only confirmed (and later) bookings take time.
 */
public interface BookingCalendar {

    /** Jobs overlapping [from, to) for one member, or for the whole team when {@code memberUserId} is null. */
    List<Busy> busy(String merchantId, @Nullable String memberUserId, Instant from, Instant to);

    /**
     * One member's jobs overlapping [from, to) with what the member's own calendar shows (S-32 write-back): the
     * display snapshot (service title, address line), the reference and the customer's id (the caller looks up the
     * first name). Nothing else about the customer.
     */
    List<Job> jobs(String merchantId, String memberUserId, Instant from, Instant to);

    /**
     * S-74: the times sent quotes propose in [from, to) — "Held for quote" on the Studio calendar. Only the latest
     * version of a quote that is still open (sent or viewed, not past its validity); ids only, no customer details.
     */
    List<QuoteHold> quoteHolds(String merchantId, Instant from, Instant to);

    record QuoteHold(String quoteId, String requestId, @Nullable String ref, @Nullable String customerId, Instant startsAt, int durationMin) {}

    record Busy(String bookingId, @Nullable String memberUserId, Instant startsAt, Instant endsAt) {}

    record Job(
            String bookingId,
            @Nullable String ref,
            String title,
            @Nullable String customerId,
            @Nullable String addressLine,
            Instant startsAt,
            Instant endsAt) {}
}
