package ca.northline.booking.web;

import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.BookingState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response records of the job endpoints. */
final class JobResponses {
    private JobResponses() {}

    record JobResponse(
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

    record JobDetailResponse(
            String id,
            @Nullable String ref,
            String title,
            Instant startsAt,
            Instant endsAt,
            BookingState state,
            @Nullable String memberUserId,
            @Nullable String memberName,
            @Nullable CustomerResponse customer,
            @Nullable String addressLine,
            @Nullable String area,
            @Nullable String access,
            @Nullable String vehicle,
            @Nullable String customerNote,
            @Nullable Long priceCents,
            @Nullable String escrow,
            @Nullable EscrowMoneyResponse escrowMoney,
            List<TimelineResponse> timeline,
            List<ApprovalResponse> approvals,
            List<String> customerPhotoIds,
            BalanceResponse balance,
            @Nullable CancellationResponse cancellation) {}

    /** S-153: the balance of a deposit quote ({@code none | pending | held | failed | cancelled}). */
    record BalanceResponse(
            String state,
            long cents,
            long taxCents,
            @Nullable Instant dueAt,
            int attempts,
            @Nullable String failure) {}

    /** S-153: the customer cancelled; {@code feeCents} (+ tax) was taken from the hold when late. */
    record CancellationResponse(Instant at, long feeCents, long feeTaxCents) {}

    /**
     * The job's escrow as the payments ledger has it: {@code heldCents} is what the customer was charged (price plus
     * GST/HST), {@code netCents} what the business receives after the GST/HST and Northline's fee.
     */
    record EscrowMoneyResponse(String state, long heldCents, long taxCents, long feeCents, long netCents) {}

    record CustomerResponse(String name, @Nullable BigDecimal reliability, long pastJobs, int noShows) {}

    record TimelineResponse(
            String type,
            Instant at,
            @Nullable String actorName,
            @Nullable String note,
            @Nullable String mediaId) {}

    record ApprovalResponse(
            String id,
            String description,
            long amountCents,
            Approval.State state,
            Instant requestedAt,
            @Nullable Instant decidedAt) {}

    record MediaResponse(String id, String fileName, String contentType, long sizeBytes) {}
}
