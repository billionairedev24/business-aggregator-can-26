package ca.northline.payments.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-156: what the standing module needs to let a seller appeal a dispute decision once (design 02 "seller may appeal
 * once"), and the money when the appeal is overturned. Only an agent's decision that cost the seller money can be
 * appealed: a full or partial refund decided in the console (S-80). A card dispute is the issuer's decision, a goodwill
 * credit is Northline's money, and a refund the business gave itself was its own choice.
 */
public interface DisputeAppeals {

    /** Design 03 copy ("You can appeal within 14 days"). */
    Duration WINDOW = Duration.ofDays(14);

    /** The dispute of this business, or empty when it isn't one of its disputes. */
    Optional<Facts> facts(String merchantId, String disputeId);

    /** The business's disputes it may still appeal now (newest decision first). */
    java.util.List<Facts> appealable(String merchantId, Instant now);

    /**
     * Northline pays the seller what the decision cost them — the refunded share of what they would have been paid
     * (after Northline's fee) — into their balance, and transfers it to their connected account. The customer keeps the
     * refund. Idempotent per appeal; returns the cents paid.
     */
    long compensate(String appealId, String disputeId, String staffId);

    /**
     * @param decision {@code full_refund | partial | release | goodwill}, null while open
     * @param agentDecided whether a Northline agent's decision (console, S-80) closed it
     * @param decidedBy who closed it (the agent; {@code stripe} for a card dispute)
     */
    record Facts(
            String disputeId,
            String caseNumber,
            String subject,
            long amountCents,
            @Nullable String decision,
            long refundCents,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt,
            boolean agentDecided,
            boolean chargeback,
            @Nullable String note) {

        /** An agent's refund decision against the seller, within {@link #WINDOW} of it. */
        public boolean appealable(Instant now) {
            return agentDecided
                    && !chargeback
                    && decidedAt != null
                    && ("full_refund".equals(decision) || "partial".equals(decision))
                    && refundCents > 0
                    && !now.isAfter(decidedAt.plus(WINDOW));
        }
    }
}
