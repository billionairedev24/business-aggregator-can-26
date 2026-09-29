package ca.northline.payments.application;

import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Refund;
import ca.northline.payments.domain.Tier;
import java.util.List;
import java.util.Optional;

/** Refunds &amp; disputes screen: the open cases, their history, and the merchant's answers. */
public interface RespondToCases {

    /**
     * @param open disputes not yet decided, newest first
     * @param awaitingRefunds refund cases waiting for the merchant (seller review)
     * @param refunds every refund case, newest first (history)
     * @param disputes every dispute, newest first (history)
     */
    record Overview(
            List<Dispute> open,
            List<Refund> awaitingRefunds,
            List<Refund> refunds,
            List<Dispute> disputes,
            int refundsLast30Days,
            SalesReadModel.DisputeRate disputeRate,
            Tier tier) {}

    record Upload(String name, String contentType, byte[] bytes) {}

    Overview overview(String merchantId);

    Dispute saveResponse(String merchantId, String disputeId, String response);

    Dispute addEvidence(String merchantId, String disputeId, Upload upload);

    Optional<DisputeEvidenceStorage.StoredFile> evidenceFile(String merchantId, String disputeId, String evidenceId);

    Dispute offerGoodwill(String merchantId, String disputeId, long amountCents);

    Dispute refundInFull(String merchantId, String disputeId, String userId);

    Dispute contest(String merchantId, String disputeId);

    Refund acceptRefund(String merchantId, String refundId);

    Refund contestRefund(String merchantId, String refundId, String reason);
}
