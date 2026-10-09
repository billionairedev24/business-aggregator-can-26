package ca.northline.trust.api;

import java.util.List;

/**
 * A customer's loyalty points ({@code trust.points_ledger}) as the account area shows them (S-58): the balance and the
 * points earned in each of the last eight weeks. Nothing credits points yet (no earning
 * rules exist; DECISIONS S-58), so a ledger without rows is a zero balance. Spending them: {@link PointsWallet}.
 */
public interface LoyaltyPoints {


    /**
     * @param balance points not yet expired (earned − redeemed − expired)
     * @param weekly points earned in each of the last eight weeks, oldest first (eight values)
     * @param pointsPerDollar the configured rate ({@link PointsWallet.Settings}): design 06's "1,240 pts = $12.40"
     */
    record Points(long balance, List<Long> weekly, int pointsPerDollar) {
        public Points {
            weekly = List.copyOf(weekly);
        }

        /** The balance in cents off anything. */
        public long valueCents() {
            return balance * 100 / pointsPerDollar;
        }
    }

    Points of(String userId);
}
