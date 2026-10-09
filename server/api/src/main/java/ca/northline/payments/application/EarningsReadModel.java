package ca.northline.payments.application;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.domain.EscrowState;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Outbound port: what the Earnings screen and the dashboard read (escrow totals, the ledger table, released net). */
public interface EarningsReadModel {

    /**
     * @param heldNetCents net of escrows held (not on hold), what the merchant will get
     * @param releasingNetCents part of {@code heldNetCents} due to release by the cut-off
     * @param onHoldGrossCents gross of escrows on hold because of a dispute / refund case
     */
    record EscrowTotals(
            long heldNetCents, int heldCount, long releasingNetCents, long onHoldGrossCents, int onHoldCount) {}

    EscrowTotals escrowTotals(String merchantId, @Nullable Instant releasingBy);

    /** Open refund cases on money already released, charged to the merchant: held back from payouts. */
    record RefundHolds(long cents, int count) {}

    RefundHolds refundHolds(String merchantId);

    /**
     * One row of the Earnings ledger table.
     *
     * @param heldCents what the customer paid for it and is held: {@code grossCents + taxCents}
     * @param grossCents the sale before tax (what the take rate applies to)
     * @param taxCents the GST/HST collected on it, which Northline remits
     * @param netCents what the merchant receives: {@code grossCents − feeCents}
     */
    record LedgerLine(
            String escrowId,
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

    /** Newest first. */
    List<LedgerLine> ledger(String merchantId, int limit);

    record Released(Instant releasedAt, long netCents, EscrowKind kind) {}

    List<Released> released(String merchantId, Instant from, Instant to);
}
