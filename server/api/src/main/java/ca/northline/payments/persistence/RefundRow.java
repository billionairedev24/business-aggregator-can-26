package ca.northline.payments.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code payments.refunds} (order_line_id / booking_id are the owning modules' refs, not mapped here). */
@Table(schema = "payments", name = "refunds")
record RefundRow(
        @Id String id,
        @Nullable String paymentIntentId,
        String merchantId,
        @Nullable String escrowId,
        @Nullable String disputeId,
        String caseNumber,
        String what,
        @Nullable String customerName,
        long amountCents,
        long taxCents,
        String reason,
        String chargedTo,
        String kind,
        boolean auto,
        String state,
        @Nullable Instant contestBy,
        @Nullable String contestReason,
        Instant createdAt,
        @Nullable Instant decidedAt,
        @Nullable Instant paidAt,
        @Nullable String stripeRefund,
        @Nullable String stripeTransferReversal,
        long reversedCents,
        long pointsCents,
        long promoReturnCents,
        @Version @Nullable Integer version) {}
