package ca.northline.trust.api;

/**
 * Spending loyalty points at checkout (mobile gaps part 2) on the balance S-58 / S-101 show. Northline funds what points
 * pay for; the rate and the cap per order are configuration ({@code northline.points.*}, docs/runbooks). The ledger
 * rows are {@code trust.points_ledger} with {@code ref_type} {@code redemption} (−points), {@code redemption_return}
 * (an abandoned checkout) or {@code refund_return} (a refund's share); each reference moves points once.
 */
public interface PointsWallet {

    /**
     * @param pointsPerDollar what $1 off costs ({@code NORTHLINE_POINTS_PER_DOLLAR}, default 100 — design 06's
     *     "1,240 pts = $12.40 off")
     * @param maxOrderPercent the share of an order's goods / food / service amount (after any promo code) points may
     *     pay ({@code NORTHLINE_POINTS_MAX_ORDER_PERCENT}, default 50)
     * @param minPoints the smallest redemption ({@code NORTHLINE_POINTS_MIN_REDEEM}, default 100 = $1)
     */
    record Settings(int pointsPerDollar, int maxOrderPercent, long minPoints) {

        public long centsOf(long points) {
            return points * 100 / pointsPerDollar;
        }

        /** The points that pay {@code cents} (rounded up, so the points never pay more than they are worth). */
        public long pointsFor(long cents) {
            return (cents * pointsPerDollar + 99) / 100;
        }
    }

    Settings settings();

    /** The person's points that can be spent now. */
    long balance(String userId);

    /** Takes {@code points} for the checkout {@code refId}; false when it was already taken. */
    boolean redeem(String userId, long points, String refId, String note);

    /**
     * Gives points back once per reference.
     *
     * @param refType {@code redemption_return} (checkout abandoned) | {@code refund_return} (a refund's share)
     */
    boolean giveBack(String userId, long points, String refType, String refId, String note);
}
