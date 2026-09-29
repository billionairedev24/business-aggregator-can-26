package ca.northline.payments.domain;

import ca.northline.payments.api.DisputeDecided;
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
    public static final Duration OFFER_WINDOW = Duration.ofHours(72);

    public static final Duration REPLY_WINDOW = Duration.ofHours(72);

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
    private final @Nullable Instant respondBy;
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
        return new DisputeDecided(Ids.next(), now, id, merchantId, escrowId, outcome.code(), refund, by);
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new Conflict(
                    state == State.DECIDED ? "case_closed" : "case_in_review",
                    state == State.DECIDED ? CaseMessages.CASE_CLOSED : "You already answered this dispute.");
        }
    }

    private void requirePendingOffer(Instant now) {
        if (offerState != OfferState.PENDING || (offerExpiresAt != null && offerExpiresAt.isBefore(now))) {
            throw new Conflict("no_pending_offer", "There is no open offer on this dispute.");
        }
    }
}
