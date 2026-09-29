package ca.northline.booking.web;

import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response records of the quote endpoints. */
final class QuoteResponses {
    private QuoteResponses() {}

    /** Everything the customer sees: every line, totals by kind, scope, exclusions, warranty, deposit, validity. */
    record QuoteResponse(
            String id,
            String requestId,
            String ref,
            int version,
            QuoteState state,
            List<LineResponse> lines,
            long labourCents,
            long partsCents,
            long feesCents,
            long discountCents,
            long subtotalCents,
            int taxBps,
            long taxCents,
            long totalCents,
            DepositKind depositKind,
            @Nullable Integer depositBps,
            long depositCents,
            String scope,
            @Nullable String exclusions,
            Warranty warranty,
            @Nullable Instant proposedAt,
            @Nullable Integer durationMin,
            int validHours,
            @Nullable Instant validUntil,
            @Nullable Instant sentAt,
            List<AttachmentResponse> attachments) {}

    /** {@code unitCents} is the amount per unit as entered; {@code amountCents} = qty × unit (negative for discounts). */
    record LineResponse(
            LineKind kind,
            String description,
            @Nullable String note,
            BigDecimal qty,
            long unitCents,
            long amountCents,
            boolean taxable) {}

    record AttachmentResponse(String id, String fileName, String contentType, long sizeBytes) {}

    /** A quote request card. {@code quote} is this merchant's draft or latest sent version. */
    record QuoteRequestResponse(
            String id,
            String ref,
            String title,
            @Nullable String customerName,
            @Nullable BigDecimal reliability,
            @Nullable String area,
            @Nullable String body,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            @Nullable QuoteResponse quote) {}
}
