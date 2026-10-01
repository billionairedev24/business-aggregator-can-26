package ca.northline.payments.application;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.domain.EscrowState;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/** Outbound port: what Sales reports, the CSV export and tax documents read. */
public interface SalesReadModel {

    /** One job or order (an escrow) by the date it happened. */
    record Sale(
            String escrowId,
            Instant occurredAt,
            EscrowKind kind,
            String label,
            @Nullable String orderNumber,
            @Nullable String customerName,
            @Nullable String listingName,
            @Nullable String source,
            long amountCents,
            long feeCents,
            long taxCents,
            EscrowState state) {}

    /** Oldest first. */
    List<Sale> sales(String merchantId, Instant from, Instant to);

    /** Refunds and credits paid, by when the case was opened. */
    long refunded(String merchantId, Instant from, Instant to);

    /**
     * @param distinct customers who bought in the period
     * @param repeat of those, customers with at least two jobs / orders with this merchant up to the period's end
     */
    record Customers(int distinct, int repeat) {}

    Customers customers(String merchantId, Instant from, Instant to);

    /** Platform refund rate for this kind of business (only published when ≥ 5 merchants contributed). */
    OptionalInt benchmarkRefundBps(EscrowKind kind);

    /** Disputes that count against the merchant (open, lost) and jobs & orders in the window. */
    record DisputeRate(int counted, int jobs) {}

    DisputeRate disputeRate(String merchantId, Instant from, Instant to);

    /**
     * Monthly totals of a year (tax documents), Edmonton months.
     *
     * @param taxRefundedCents GST/HST given back with refunds paid in the month (S-21)
     */
    record Month(
            YearMonth month,
            long grossCents,
            long feeCents,
            long taxCents,
            long refundedCents,
            long taxRefundedCents,
            long paidOutCents) {}

    List<Month> months(String merchantId, int year);
}
