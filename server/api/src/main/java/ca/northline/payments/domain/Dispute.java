package ca.northline.payments.domain;

import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeUpdated;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A dispute {@code DS-…} on one escrow (aggregate root). Opening it pauses that escrow. The merchant answers with a
 * written response and evidence, then either offers goodwill (the customer has 72 h to accept; declined → agent),
 * refunds in full, or contests (a Northline agent decides within 2 business days).
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Dispute {

    /** Design 02: "Customer has 72 h to accept." Also the merchant's time to reply ("reply by Thu"). */
    public static final Duration OFFER_WINDOW = Duration.ofDays(3);

    public static final Duration REPLY_WINDOW = Duration.ofDays(3);

    public enum State implements CodedEnum {
        OPEN,
        SELLER_REPLIED,
        AGENT,
        DECIDED,
        APPEALED
    }

    public enum Decision implements CodedEnum {
        FULL_REFUND,
        PARTIAL,
        RELEASE,
        GOODWILL
    }

    public enum OfferState implements CodedEnum {
        PENDING,
        ACCEPTED,
        DECLINED,
        EXPIRED
    }

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String escrowId;
    private final String merchantId;
    private final String caseNumber;
    private final String subject;
    private final long amountCents;
    private final @Nullable String customerName;
    private final @Nullable String customerStatement;
    private final @Nullable String openedBy;
    private final List<Evidence> evidence;
    private @Nullable String response;
    private @Nullable Instant responseUpdatedAt;
    private @Nullable Long offerCents;
    private @Nullable OfferState offerState;
    private @Nullable Instant offerExpiresAt;

    @ToString.Include
    private State state;

    private @Nullable Decision decision;
    private @Nullable String decidedBy;
    private @Nullable Long refundCents;
    private @Nullable Instant respondBy;
    /** Card disputes (chargebacks): Stripe's dispute, its status and reason, and when the last event was applied. */
    private @Nullable String stripeDispute;

    private @Nullable String stripeStatus;
    private @Nullable String stripeReason;
    private @Nullable Instant stripeUpdatedAt;
    private final Instant openedAt;
    private @Nullable Instant decidedAt;
    private final @Nullable Integer version;

    public static Dispute open(
            String caseNumber, Escrow escrow, String customerId, String subject, String statement, Instant now) {
        return Dispute.builder()
                .id(Ids.next())
                .escrowId(escrow.getId())
                .merchantId(escrow.getMerchantId())
                .caseNumber(caseNumber)
                .subject(subject)
                .amountCents(escrow.getAmountCents())
                .customerName(escrow.getCustomerName())
                .customerStatement(statement)
                .openedBy(customerId)
                .evidence(new ArrayList<>())
                .state(State.OPEN)
                .respondBy(now.plus(REPLY_WINDOW))
                .openedAt(now)
                .build();
    }

    /** Northline answers a card dispute this long before the issuer's deadline, so an agent can submit the evidence. */
    public static final Duration CHARGEBACK_LEAD = Duration.ofDays(2);

    public static final String CARD_DISPUTE = "The card issuer decides this dispute. Send your response and evidence.";

    /**
     * The customer's bank disputed the card payment (Stripe {@code charge.dispute.created}): a case in the same flow,
     * which the merchant answers with a response and evidence. Stripe has already taken the money back from Northline;
     * the escrow is put on hold until the bank decides.
     */
    public static Dispute chargeback(
            String caseNumber,
            Escrow escrow,
            String stripeDispute,
            String reason,
            long amountCents,
            @Nullable Instant issuerDeadline,
            Instant eventAt,
            Instant now) {
        var respondBy = issuerDeadline == null ? now.plus(REPLY_WINDOW) : issuerDeadline.minus(CHARGEBACK_LEAD);
        return Dispute.builder()
                .id(Ids.next())
                .escrowId(escrow.getId())
                .merchantId(escrow.getMerchantId())
                .caseNumber(caseNumber)
                .subject("Card dispute · " + reason.replace('_', ' '))
                .amountCents(Math.min(amountCents, escrow.getAmountCents()))
                .customerName(escrow.getCustomerName())
                .customerStatement("The customer's bank disputed the card payment (" + reason.replace('_', ' ') + ").")
                .openedBy(escrow.getCustomerId())
                .evidence(new ArrayList<>())
                .state(State.OPEN)
                .respondBy(respondBy.isBefore(now) ? now : respondBy)
                .openedAt(now)
                .stripeDispute(stripeDispute)
                .stripeReason(reason)
                .stripeUpdatedAt(eventAt)
                .build();
    }

    public boolean isChargeback() {
        return stripeDispute != null;
    }

    /** A case the customer opened turned into a card dispute too. */
    public void attachChargeback(String dispute, String reason) {
        stripeDispute = dispute;
        stripeReason = reason;
    }

    /**
     * Stripe's status changed. Events can arrive out of order: one older than the last applied is ignored. Returns
     * false when ignored.
     */
    public boolean chargebackUpdated(String status, @Nullable Instant issuerDeadline, Instant eventAt, Instant now) {
        if (stripeUpdatedAt != null && eventAt.isBefore(stripeUpdatedAt)) {
            return false;
        }
        stripeStatus = status;
        stripeUpdatedAt = eventAt;
        if (issuerDeadline != null && state != State.DECIDED) {
            var by = issuerDeadline.minus(CHARGEBACK_LEAD);
            respondBy = by.isBefore(now) ? now : by;
        }
        return true;
    }

    /** The bank decided: won keeps the money with the merchant, lost refunds it all. Once only. */
    public java.util.Optional<DisputeDecided> chargebackClosed(boolean won, Instant now) {
        if (state == State.DECIDED) {
            return java.util.Optional.empty();
        }
        if (offerState == OfferState.PENDING) {
            offerState = OfferState.EXPIRED;
        }
        return java.util.Optional.of(
                close(won ? Decision.RELEASE : Decision.FULL_REFUND, won ? 0 : amountCents, "stripe", now));
    }

    public List<Evidence> getEvidence() {
        return List.copyOf(evidence);
    }

    public boolean decided() {
        return state == State.DECIDED;
    }

    /** "Winning a dispute never penalises you"; a goodwill offer the customer accepted doesn't count either. */
    public boolean countsAgainstDisputeRate() {
        return state != State.DECIDED || decision == Decision.FULL_REFUND || decision == Decision.PARTIAL;
    }

    /** Draft response; editable until the merchant acts on the case. */
    public void saveResponse(String text, Instant now) {
        requireOpen();
        var trimmed = text.strip();
        if (trimmed.length() > CaseMessages.RESPONSE_MAX) {
            throw RuleViolation.of("response", "length", CaseMessages.RESPONSE_TOO_LONG);
        }
        response = trimmed.isEmpty() ? null : trimmed;
        responseUpdatedAt = now;
    }

    public void addEvidence(Evidence item) {
        if (state == State.DECIDED) {
            throw new Conflict("case_closed", CaseMessages.CASE_CLOSED);
        }
        if (evidence.stream().filter(e -> e.storageKey() != null).count() >= Evidence.MAX_FILES) {
            throw RuleViolation.of("file", "count", CaseMessages.EVIDENCE_COUNT);
        }
        evidence.add(item);
    }

    /** "Send 50% goodwill offer": the customer has 72 h to accept. */
    public void offerGoodwill(long cents, Instant now) {
        requireOpen();
        requireNotChargeback();
        if (cents <= 0 || cents >= amountCents) {
            throw RuleViolation.of("amountCents", "range", CaseMessages.OFFER_RANGE);
        }
        offerCents = cents;
        offerState = OfferState.PENDING;
        offerExpiresAt = now.plus(OFFER_WINDOW);
        state = State.SELLER_REPLIED;
    }

    /** "Full refund": the merchant gives the whole amount back; the case closes. */
    public DisputeDecided refundInFull(String ownerId, Instant now) {
        requireOpen();
        requireNotChargeback();
        return close(Decision.FULL_REFUND, amountCents, ownerId, now);
    }

    /** "Contest — send to agent": needs the written response. */
    public void contest(Instant now) {
        requireOpen();
        if (response == null || response.isBlank()) {
            throw RuleViolation.of("response", "required", CaseMessages.RESPONSE_REQUIRED);
        }
        responseUpdatedAt = now;
        state = State.AGENT;
    }

    public DisputeDecided acceptOffer(String customerId, Instant now) {
        requirePendingOffer(now);
        offerState = OfferState.ACCEPTED;
        return close(Decision.GOODWILL, java.util.Objects.requireNonNull(offerCents), customerId, now);
    }

    public void declineOffer(Instant now) {
        requirePendingOffer(now);
        offerState = OfferState.DECLINED;
        state = State.AGENT;
    }

    /** An unanswered offer lapses after 72 h and the case goes to an agent. */
    public boolean expireOffer(Instant now) {
        if (offerState == OfferState.PENDING && offerExpiresAt != null && !offerExpiresAt.isAfter(now)) {
            offerState = OfferState.EXPIRED;
            state = State.AGENT;
            return true;
        }
        return false;
    }

    /**
     * {@code dispute.updated} (the merchant's team is emailed): {@code opened} (with the reply deadline),
     * {@code offer_declined}, {@code offer_expired}.
     */
    public DisputeUpdated updated(String change, Instant now) {
        return new DisputeUpdated(
                Ids.next(),
                now,
                id,
                merchantId,
                caseNumber,
                change,
                amountCents,
                "opened".equals(change) ? respondBy : null);
    }

    /** A Northline agent decides. */
    public DisputeDecided decide(Decision outcome, long refund, String agentId, Instant now) {
        if (state == State.DECIDED) {
            throw new Conflict("case_closed", CaseMessages.CASE_CLOSED);
        }
        var cents = switch (outcome) {
            case RELEASE -> 0L;
            case FULL_REFUND -> amountCents;
            case PARTIAL, GOODWILL -> {
                if (refund <= 0 || refund >= amountCents) {
                    throw RuleViolation.of("refundCents", "range", CaseMessages.OFFER_RANGE);
                }
                yield refund;
            }
        };
        if (offerState == OfferState.PENDING) {
            offerState = OfferState.EXPIRED;
        }
        return close(outcome, cents, agentId, now);
    }

    private DisputeDecided close(Decision outcome, long refund, String by, Instant now) {
        state = State.DECIDED;
        decision = outcome;
        refundCents = refund;
        decidedBy = by;
        decidedAt = now;
        return new DisputeDecided(
                Ids.next(), now, id, merchantId, caseNumber, amountCents, escrowId, outcome.code(), refund, by);
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new Conflict(
                    state == State.DECIDED ? "case_closed" : "case_in_review",
                    state == State.DECIDED ? CaseMessages.CASE_CLOSED : "You already answered this dispute.");
        }
    }

    /** A disputed card payment can't be refunded or settled by Northline: the issuer decides. */
    private void requireNotChargeback() {
        if (isChargeback()) {
            throw new Conflict("card_dispute", CARD_DISPUTE);
        }
    }

    private void requirePendingOffer(Instant now) {
        if (offerState != OfferState.PENDING || (offerExpiresAt != null && offerExpiresAt.isBefore(now))) {
            throw new Conflict("no_pending_offer", "There is no open offer on this dispute.");
        }
    }
}
