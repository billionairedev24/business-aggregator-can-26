package ca.northline.payments.api;

/**
 * Closing disputes and contested refunds from outside the Studio: the customer answers a goodwill offer (consumer app) and a Northline agent
 * decides contested cases (console). Each closing path publishes {@link DisputeDecided}.
 */
public interface DisputeDecisions {

    enum Decision {
        /** Merchant keeps the money ("Won · evidence"). */
        RELEASE,
        /** Customer gets {@code refundCents} back. */
        PARTIAL,
        FULL_REFUND
    }

    void acceptOffer(String disputeId, String customerId);

    /** The customer declined the offer: the case goes to an agent. */
    void declineOffer(String disputeId, String customerId);

    void decide(String disputeId, Decision decision, long refundCents, String agentId);

    /** A Northline agent decides a contested refund case; a denied refund releases the hold on the payment. */
    void decideRefund(String refundId, boolean approve, String agentId);
}
