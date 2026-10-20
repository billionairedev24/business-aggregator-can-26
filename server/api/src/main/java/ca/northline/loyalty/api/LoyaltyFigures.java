package ca.northline.loyalty.api;

/** S-152: the console finance screen's loyalty figures. */
public interface LoyaltyFigures {

    /**
     * @param members households with an active, trialing or retrying Plus membership
     * @param trialing of those, in their free trial
     * @param pendingReferrals referrals waiting for the invitee's first completed sale
     */
    record Figures(long members, long trialing, long pendingReferrals) {}

    Figures figures();
}
