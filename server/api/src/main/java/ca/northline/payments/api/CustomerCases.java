package ca.northline.payments.api;

/**
 * What a customer can start (consumer app "Something's wrong"). Refunds are never instant: every request goes to the
 * merchant's review queue first (under $25: approved automatically unless the merchant contests within 48 h; otherwise
 * the merchant has 24 h, then a Northline agent decides), and approved refunds are paid by the refund queue. The money
 * in question is held back from the merchant's payouts meanwhile.
 */
public interface CustomerCases {

    /** Opens refund case {@code RF-…} on an escrow; returns the refund id. */
    String requestRefund(String escrowId, String customerId, long amountCents, String what);

    /** Opens dispute {@code DS-…} on an escrow (escrow goes on hold); returns the dispute id. */
    String openDispute(String escrowId, String customerId, String subject, String statement);
}
