package ca.northline.payments.web;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.application.ViewSalesReport;
import ca.northline.payments.domain.EscrowState;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.payments.domain.Tier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** JSON of the Earnings and Reports screens. */
final class EarningsResponses {
    private EarningsResponses() {}

    /**
     * {@code GET /earnings}. {@code takeRates} = bps per tier ("Master (Trusted 12%, Registered 15%)").
     *
     * @param frequency payout schedule (the "4 · Weekly (or instant)" step and the headline wording)
     */
    record Overview(
            long headlineCents,
            long availableCents,
            long escrowNetCents,
            int escrowCount,
            long onHoldCents,
            int onHoldDisputes,
            int onHoldRefunds,
            @Nullable Instant nextPayoutAt,
            PayoutSchedule.Frequency frequency,
            Tier tier,
            int takeRateBps,
            Map<String, Integer> takeRates) {}

    /**
     * One row of the ledger table ({@code GET /earnings/ledger}): {@code heldCents} is what the customer was charged
     * (sale plus GST/HST), {@code grossCents} the sale before tax, {@code netCents} the business's share after the tax
     * and Northline's fee.
     */
    record LedgerLine(
            String id,
            EscrowKind kind,
            String label,
            @Nullable String orderNumber,
            Instant occurredAt,
            @Nullable String customerName,
            long heldCents,
            long grossCents,
            long taxCents,
            long feeCents,
            long netCents,
            EscrowState state,
            @Nullable Instant releaseAt,
            @Nullable Instant releasedAt) {}

    /** {@code GET /reports?period=}. Percentages: {@code …Pct} whole percent, {@code …Bps} basis points. */
    record Report(
            ViewSalesReport.Period period,
            Instant from,
            Instant to,
            long grossCents,
            @Nullable Integer grossChangePct,
            int count,
            long averageTicketCents,
            @Nullable Integer repeatCustomerPct,
            int refundRateBps,
            @Nullable Integer benchmarkRefundRateBps,
            ViewSalesReport.Granularity granularity,
            List<ViewSalesReport.Point> series,
            List<ViewSalesReport.ListingTotal> byListing,
            List<ViewSalesReport.SourceShare> sources) {}
}
