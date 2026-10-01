package ca.northline.trust.api;

import java.util.List;

/**
 * A customer's loyalty points ({@code trust.points_ledger}) as the account area shows them (S-58): the balance and the
 * points earned in each of the last eight weeks. A read model only — nothing credits or redeems points yet (no earning
 * rules exist; DECISIONS S-58), so a ledger without rows is a zero balance.
 */
public interface LoyaltyPoints {

    /** What a point is worth: 100 points = $1 (design 06 wallet, "1,240 pts = $12.40 off anything"). */
    int POINTS_PER_DOLLAR = 100;

    /**
     * @param balance points not yet expired (earned − redeemed − expired)
     * @param weekly points earned in each of the last eight weeks, oldest first (eight values)
     */
    record Points(long balance, List<Long> weekly) {
        public Points {
            weekly = List.copyOf(weekly);
        }

        /** The balance in cents off anything. */
        public long valueCents() {
            return balance * 100 / POINTS_PER_DOLLAR;
        }
    }

    Points of(String userId);
}
