package ca.northline.loyalty.api;

/**
 * S-152: what Northline Plus gives a person right now (through their household's membership), as configuration
 * ({@code northline.plus.*}): no service fee on food orders, the member prices shops opted into, and more points.
 * Checkout asks with the buyer's id; a person without Plus gets {@link Benefits#NONE}.
 *
 * @see ca.northline.loyalty.api.LoyaltyFigures
 */
public interface PlusBenefits {

    /**
     * @param member the person's household has an active (or trialing, or retrying) membership
     * @param serviceFeeWaived food orders' service fee is $0
     * @param memberPrices shop offers' member prices apply
     * @param pointsMultiplierPct points earned × this / 100 (100 = no extra)
     */
    record Benefits(boolean member, boolean serviceFeeWaived, boolean memberPrices, int pointsMultiplierPct) {
        public static final Benefits NONE = new Benefits(false, false, false, 100);
    }

    Benefits of(String userId);

    /** A member price: {@code unitCents} less {@code pct} %, rounded to the cent. */
    static long memberPrice(long unitCents, int pct) {
        return unitCents - Math.round(unitCents * pct / 100.0);
    }
}
