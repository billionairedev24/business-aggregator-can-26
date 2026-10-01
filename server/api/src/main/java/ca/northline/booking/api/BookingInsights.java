package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Booking figures for the Studio dashboard. */
public interface BookingInsights {

    /** Jobs starting in [from, to), earliest first (cancelled excluded). */
    List<JobAtAGlance> jobs(String merchantId, Instant from, Instant to);

    /** Number of jobs (not cancelled) starting in [from, to). */
    long jobCount(String merchantId, Instant from, Instant to);

    /** S-75: bookings made (created, not cancelled) in [from, to) — the "booked" side of the storefront's visits. */
    long bookingsMade(String merchantId, Instant from, Instant to);

    /** Quote requests still waiting for this merchant's quote. */
    QuoteInbox quoteInbox(String merchantId, Instant now);

    /** Of the last {@code lastN} completed jobs, how many were completed without photos. */
    PhotoCoverage photoCoverage(String merchantId, int lastN);

    /** Title of a job ("Pre-purchase inspection"), for case descriptions. */
    Optional<String> jobTitle(String bookingId);

    record JobAtAGlance(
            String id,
            String title,
            Instant startsAt,
            String state,
            @Nullable String customerId,
            @Nullable String memberUserId,
            @Nullable String area,
            @Nullable String access,
            @Nullable Long escrowHeldCents) {}

    /** {@code open} requests without a sent quote; the earliest respond-by among them. */
    record QuoteInbox(int open, @Nullable Instant earliestRespondBy) {}

    record PhotoCoverage(int jobs, int withoutPhotos) {}
}
