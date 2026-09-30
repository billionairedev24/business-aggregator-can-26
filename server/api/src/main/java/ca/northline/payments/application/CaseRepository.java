package ca.northline.payments.application;

import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Refund;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: refund cases and disputes. */
public interface CaseRepository {

    /** Next human case number with the prefix ({@code RF}, {@code DS}), e.g. {@code RF-3001}. */
    String nextCaseNumber(String prefix);

    Optional<Refund> refund(String merchantId, String refundId);

    Optional<Refund> refund(String refundId);

    void insert(Refund refund);

    void update(Refund refund);

    /** Refund cases past their contest deadline still waiting for the merchant. */
    List<Refund> lapsedRefunds(Instant now, int limit);

    /** The refund queue: approved, not yet paid, oldest first. */
    List<Refund> approvedRefunds(int limit);

    /** Newest first. */
    List<Refund> refunds(String merchantId, int limit);

    Optional<Dispute> dispute(String merchantId, String disputeId);

    Optional<Dispute> dispute(String disputeId);

    void insert(Dispute dispute);

    void update(Dispute dispute);

    List<Dispute> expiredOffers(Instant now, int limit);

    Optional<Dispute> disputeByStripeId(String stripeDispute);

    /** The customer's open case on an escrow that isn't a card dispute yet. */
    Optional<Dispute> openDisputeOn(String escrowId);

    /** Mirrors Stripe's refund status; false when no refund has that Stripe id (made outside Northline). */
    boolean refundStripeStatus(String stripeRefund, String status);

    /** Newest first. */
    List<Dispute> disputes(String merchantId, int limit);

    /** Disputes awaiting the merchant's answer + refund cases in seller review (the sidebar badge). */
    int awaitingMerchant(String merchantId);
}
