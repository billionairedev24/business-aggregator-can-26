package ca.northline.booking.application;

import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.BookingState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: read models over {@code booking.*} for the Appointments screen (ids only; names come later). */
public interface JobQueries {

    /** Jobs of a merchant starting in [from, to); only {@code memberUserId}'s when not null. */
    List<JobRow> jobs(String merchantId, Instant from, Instant to, @Nullable String memberUserId);

    Optional<JobCard> card(String merchantId, String bookingId);

    List<LogRow> log(String bookingId);

    List<Approval> approvals(String bookingId);

    /** Completed or signed-off jobs of this customer with this merchant before {@code before}. */
    long pastJobs(String merchantId, String customerId, Instant before);

    record JobRow(
            String id,
            @Nullable String ref,
            @Nullable String title,
            Instant startsAt,
            Instant endsAt,
            BookingState state,
            @Nullable String memberUserId,
            @Nullable String customerId,
            @Nullable String area,
            @Nullable Long priceCents) {}

    record JobCard(
            JobRow job,
            @Nullable String addressLine,
            @Nullable String access,
            @Nullable String vehicle,
            @Nullable String customerNote,
            boolean paid) {}

    record LogRow(
            String type,
            Instant at,
            String actorId,
            @Nullable String note,
            @Nullable String mediaId) {}
}
