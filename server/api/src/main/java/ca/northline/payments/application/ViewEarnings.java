package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.payments.domain.Tier;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Earnings screen: headline, KPIs and the ledger table. */
public interface ViewEarnings {

    /**
     * @param headlineCents available now + escrow releasing before the next payout ("$2,140.60 releasing Friday")
     * @param availableCents released to the balance, minus money held for open refund cases ("released, next payout")
     * @param escrowNetCents net the merchant will get from held escrow ("in escrow · 11 jobs")
     * @param onHoldCents gross of escrow on hold + refund cases held back ("on hold · 1 dispute")
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
            int takeRateBps) {}

    Overview overview(String merchantId);

    List<EarningsReadModel.LedgerLine> ledger(String merchantId, int limit);
}
