package ca.northline.payments.api;

import ca.northline.shared.Bytes;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-80: the console's disputes &amp; refunds queue as payments owns it — disputes that went to an agent (contested,
 * offer declined or expired) and refund cases escalated after the seller's 24 h (S-60), the evidence, and the agent's
 * decision. Decisions move the escrow through the existing case paths (refund queue, release); nothing is refunded
 * without an agent, and a decision returning more than {@link #COSIGN_ABOVE_CENTS} waits for a finance co-sign.
 */
public interface AgentCases {

    /** Design 03 Team: Finance — "refunds &gt; $500 · Passkey + 2nd approver". */
    long COSIGN_ABOVE_CENTS = 50_000;

    String OUTCOME_REQUIRED = "Choose an outcome.";
    String PARTIAL_RANGE = "A partial refund is more than $0 and less than the amount in escrow.";
    String REFUND_OUTCOMES = "A refund case is refunded in full or released to the seller.";
    String COSIGN_SELF = "Another person must co-sign this decision.";

    enum Outcome implements CodedEnum {
        FULL_REFUND,
        PARTIAL,
        RELEASE,
        /** The seller keeps the money; Northline gives the customer a credit (the platform pays). */
        GOODWILL_CREDIT
    }

    /**
     * @param forAgent cases waiting for an agent (or a co-sign)
     * @param inSellerWindow refund cases the seller still has time to answer
     * @param closedThisWeek disputes and refund cases closed in the last 7 days
     */
    record Summary(long forAgent, long inSellerWindow, long closedThisWeek) {}

    Summary summary(MerchantScope scope, Instant weekAgo);

    /** Cases waiting for an agent or a co-sign (oldest first), then cases agents decided since {@code decidedSince}. */
    List<CaseRow> queue(MerchantScope scope, Instant decidedSince, int limit);

    /** @param kind {@code dispute | refund} */
    Optional<CaseDetail> detail(String kind, String caseId);

    CaseRow decide(Decide command);

    CaseRow cosign(Cosign command);

    Optional<EvidenceFile> evidence(String disputeId, String evidenceId);

    /** @param refundCents for {@link Outcome#PARTIAL} and {@link Outcome#GOODWILL_CREDIT}; ignored otherwise */
    record Decide(
            String kind,
            String caseId,
            Outcome outcome,
            long refundCents,
            @Nullable String note,
            String staffId,
            String role) {}

    record Cosign(
            String decisionId, boolean approve, @Nullable String note, String staffId, String role) {}

    /**
     * One case.
     *
     * @param kind {@code dispute | refund}
     * @param state {@code agent} (waiting), {@code awaiting_cosign}, {@code decided}, or the case's own state
     * @param sellerStatement the seller's response (dispute) or contest reason (refund case)
     * @param pending the decision waiting for a co-sign, if any
     * @param decision the applied agent decision, if any
     */
    record CaseRow(
            String kind,
            String id,
            String caseNumber,
            String merchantId,
            String subject,
            long amountCents,
            @Nullable String customerName,
            @Nullable String customerStatement,
            @Nullable String sellerStatement,
            String state,
            Instant openedAt,
            @Nullable AgentDecision pending,
            @Nullable AgentDecision decision) {}

    record AgentDecision(
            String id,
            String outcome,
            long refundCents,
            @Nullable String note,
            String decidedBy,
            Instant decidedAt,
            String state,
            @Nullable String cosignedBy,
            @Nullable Instant cosignedAt,
            @Nullable String cosignNote) {}

    /**
     * @param customerPriorDisputes other disputes the same customer opened
     * @param sellerPriorDisputes other disputes of the business; {@code sellerPriorWon} of them released to it
     */
    record CaseDetail(
            CaseRow row,
            List<EvidenceItem> evidence,
            int customerPriorDisputes,
            int sellerPriorDisputes,
            int sellerPriorWon,
            @Nullable Long offerCents,
            @Nullable String offerState) {
        public CaseDetail {
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * @param kind {@code photo | report | gps | document}
     * @param by {@code merchant | customer}
     * @param file whether a stored file can be downloaded
     */
    record EvidenceItem(
            String id,
            String kind,
            String name,
            @Nullable String contentType,
            long size,
            String by,
            Instant at,
            boolean file) {}

    record EvidenceFile(Bytes bytes, String contentType, String name) {}
}
