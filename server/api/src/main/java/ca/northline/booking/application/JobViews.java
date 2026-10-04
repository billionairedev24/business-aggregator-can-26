package ca.northline.booking.application;

import ca.northline.booking.api.JobEscrows;
import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.BookingState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the Appointments screen. Customer names are the short form ("A. Osei") except on the job card. */
public final class JobViews {
    private JobViews() {}

    /** A job in the calendar / list. {@code memberName} is the first name ("Jas"). */
    public record JobSummary(
            String id,
            @Nullable String ref,
            String title,
            Instant startsAt,
            Instant endsAt,
            BookingState state,
            @Nullable String memberUserId,
            @Nullable String memberName,
            @Nullable String customerName,
            @Nullable String area,
            @Nullable Long priceCents) {}

    /** The job card on the right of the Appointments screen. */
    public record JobDetail(
            String id,
            @Nullable String ref,
            String title,
            Instant startsAt,
            Instant endsAt,
            BookingState state,
            @Nullable String memberUserId,
            @Nullable String memberName,
            @Nullable Customer customer,
            @Nullable String addressLine,
            @Nullable String area,
            @Nullable String access,
            @Nullable String vehicle,
            @Nullable String customerNote,
            @Nullable Long priceCents,
            // held while the job runs, released after sign-off, null without payment (the payments ledger's state)
            @Nullable String escrow,
            // what was charged and is held, its GST/HST, Northline's fee and the merchant's net; null without payment
            JobEscrows.@Nullable Held escrowMoney,
            List<TimelineEntry> timeline,
            List<Approval> approvals) {}

    public record Customer(String name, @Nullable BigDecimal reliability, long pastJobs) {}

    public record TimelineEntry(
            String type,
            Instant at,
            @Nullable String actorName,
            @Nullable String note,
            @Nullable String mediaId) {}
}
