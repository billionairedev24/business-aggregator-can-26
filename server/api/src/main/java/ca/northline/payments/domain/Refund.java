package ca.northline.payments.domain;

import ca.northline.payments.api.RefundCaseUpdated;
import ca.northline.payments.api.RefundIssued;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A refund case {@code RF-…} (aggregate root). Refunds are never instant (chat 1: "issue flow needs to go to queue"):
 * a request waits for the merchant — under $25 it is approved automatically unless contested within 48 h, otherwise
 * the merchant has 24 h before a Northline agent decides — and an approved refund waits for the refund queue, which
 * pays it. While the case is open the money is held back from the merchant's payouts.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Refund {

    /** Design 02: "Small refunds (under $25) are auto-approved … unless you contest within 48 h". */
    public static final long AUTO_APPROVE_BELOW_CENTS = 2500;

    public static final Duration SMALL_CONTEST_WINDOW = Duration.ofDays(2);
    /** Chat 1: "submitted → seller has 24 h → agent decides in 2 business days → refund lands". */
    public static final Duration SELLER_REVIEW_WINDOW = Duration.ofHours(24);

    public enum State implements CodedEnum {
        REQUESTED,
        SELLER_REVIEW,
        AGENT_REVIEW,
        APPROVED,
        DENIED,
        PAID
    }

    public enum Kind implements CodedEnum {
        /** Money back to the original payment method. */
        REFUND,
        /** Northline credit (goodwill), funded by the platform. */
        CREDIT
    }

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final @Nullable String paymentIntentId;
    private final String merchantId;
    private final @Nullable String escrowId;
    private final @Nullable String disputeId;
    private final String caseNumber;
    private final String what;
    private final @Nullable String customerName;
    private final long amountCents;
    /** GST/HST given back on top of the refunded amount: the refunded share of the tax the sale collected (S-21). */
    private final long taxCents;

    private final String reason;
    private final ChargedTo chargedTo;
    private final Kind kind;
    private final boolean auto;

    @ToString.Include
    private State state;

    private final @Nullable Instant contestBy;
    private @Nullable String contestReason;
    private final Instant createdAt;
    private @Nullable Instant decidedAt;
    private @Nullable Instant paidAt;
    private @Nullable String stripeRefund;
    private @Nullable String stripeTransferReversal;
    private long reversedCents;
    // Mobile gaps part 2: the refunded share of what points paid (back to the wallet, not the card), and of a
    // Northline-funded code's top-up (taken back from the merchant when the escrow had been released, so topped up).
    private final long pointsCents;

    private final long promoReturnCents;

    private final @Nullable Integer version;

    /** A customer asked for money back on {@code escrow}. */
    public static Refund requested(String caseNumber, Escrow escrow, long amountCents, String what, Instant now) {
        return requested(caseNumber, escrow, amountCents, what, now, amountCents < AUTO_APPROVE_BELOW_CENTS);
    }

    /**
     * A customer asked for money back on {@code escrow}. With {@code autoApprove} false (S-60's "Something's wrong")
     * nothing is approved by the clock: the seller accepts, or a Northline agent decides once the seller contests or
     * lets the 24 h pass.
     */
    public static Refund requested(
            String caseNumber, Escrow escrow, long amountCents, String what, Instant now, boolean autoApprove) {
        if (amountCents <= 0 || amountCents > escrow.getAmountCents()) {
            throw RuleViolation.of("amountCents", "range", "Refund between $0.01 and the amount paid.");
        }
        var auto = autoApprove && amountCents < AUTO_APPROVE_BELOW_CENTS;
        return Refund.builder()
                .id(Ids.next())
                .paymentIntentId(escrow.getPaymentIntentId())
                .merchantId(escrow.getMerchantId())
                .escrowId(escrow.getId())
                .caseNumber(caseNumber)
                .what(what)
                .customerName(escrow.getCustomerName())
                .amountCents(amountCents)
                .taxCents(CanadianTax.refundShare(amountCents, escrow.getAmountCents(), escrow.getTaxCents()))
                .pointsCents(pointsShare(amountCents, escrow))
                .promoReturnCents(
                        CanadianTax.refundShare(amountCents, escrow.getAmountCents(), escrow.northlineDiscountCents()))
                .reason("customer_request")
                .chargedTo(ChargedTo.MERCHANT)
                .kind(Kind.REFUND)
                .auto(auto)
                .state(State.SELLER_REVIEW)
                .contestBy(now.plus(auto ? SMALL_CONTEST_WINDOW : SELLER_REVIEW_WINDOW))
                .createdAt(now)
                .build();
    }

    /** The refund a dispute decision owes the customer — approved, waiting for the queue. */
    public static Refund fromDispute(String caseNumber, Dispute dispute, Escrow escrow, long amountCents, Instant now) {
        return Refund.builder()
                .id(Ids.next())
                .paymentIntentId(escrow.getPaymentIntentId())
                .merchantId(escrow.getMerchantId())
                .escrowId(escrow.getId())
                .disputeId(dispute.getId())
                .caseNumber(caseNumber)
                .what(dispute.getSubject())
                .customerName(dispute.getCustomerName())
                .amountCents(amountCents)
                .taxCents(CanadianTax.refundShare(amountCents, escrow.getAmountCents(), escrow.getTaxCents()))
                .pointsCents(pointsShare(amountCents, escrow))
                .promoReturnCents(
                        CanadianTax.refundShare(amountCents, escrow.getAmountCents(), escrow.northlineDiscountCents()))
                .reason("dispute")
                .chargedTo(ChargedTo.MERCHANT)
                .kind(Kind.REFUND)
                .auto(false)
                .state(State.APPROVED)
                .createdAt(now)
                .decidedAt(now)
                .build();
    }

    /**
     * S-80 "Goodwill credit (platform pays)": the seller keeps the money; Northline gives the customer a credit for
     * {@code amountCents}, approved by the agent's decision and posted by the refund queue (no card money moves).
     */
    public static Refund goodwillCredit(
            String caseNumber, Dispute dispute, Escrow escrow, long amountCents, Instant now) {
        return Refund.builder()
                .id(Ids.next())
                .paymentIntentId(escrow.getPaymentIntentId())
                .merchantId(escrow.getMerchantId())
                .escrowId(escrow.getId())
                .disputeId(dispute.getId())
                .caseNumber(caseNumber)
                .what(dispute.getSubject())
                .customerName(dispute.getCustomerName())
                .amountCents(amountCents)
                .taxCents(0)
                .reason("goodwill")
                .chargedTo(ChargedTo.PLATFORM)
                .kind(Kind.CREDIT)
                .auto(false)
                .state(State.APPROVED)
                .createdAt(now)
                .decidedAt(now)
                .build();
    }

    /**
     * What goes back to the customer's card: the amount plus the tax on it, less the share points paid (that goes back
     * to the wallet). A credit gives back no tax.
     */
    public long cardCents() {
        return amountCents + taxCents - pointsCents;
    }

    /** The share of the points an escrow's refund of {@code amountCents} gives back (all of them for the whole). */
    static long pointsShare(long amountCents, Escrow escrow) {
        return CanadianTax.refundShare(amountCents, escrow.getAmountCents(), escrow.getPointsCents());
    }

    /** Money the merchant can't pay out while this case is open. */
    public boolean holdsMerchantMoney() {
        return chargedTo == ChargedTo.MERCHANT
                && (state == State.SELLER_REVIEW || state == State.AGENT_REVIEW || state == State.APPROVED);
    }

    public boolean awaitingMerchant() {
        return state == State.SELLER_REVIEW;
    }

    /** The merchant accepts: approved, queued (never paid on the spot). */
    public void accept(Instant now) {
        requireSellerReview();
        state = State.APPROVED;
        decidedAt = now;
    }

    /** The merchant contests: a Northline agent decides within 2 business days. */
    public void contest(String reason) {
        requireSellerReview();
        if (reason.isBlank()) {
            throw RuleViolation.of("reason", "required", CaseMessages.CONTEST_REASON_REQUIRED);
        }
        state = State.AGENT_REVIEW;
        contestReason = reason.strip();
    }

    /** The merchant didn't answer in time: small refunds are approved, the rest go to an agent. */
    public boolean lapse(Instant now) {
        if (state != State.SELLER_REVIEW || contestBy == null || contestBy.isAfter(now)) {
            return false;
        }
        if (auto) {
            state = State.APPROVED;
            decidedAt = now;
        } else {
            state = State.AGENT_REVIEW;
        }
        return true;
    }

    /** A Northline agent decides a contested refund. */
    public void decide(boolean approve, Instant now) {
        if (state != State.AGENT_REVIEW && state != State.SELLER_REVIEW) {
            throw new Conflict("case_closed", CaseMessages.CASE_CLOSED);
        }
        state = approve ? State.APPROVED : State.DENIED;
        decidedAt = now;
    }

    /**
     * {@code refund.case_updated} for the case's current state (the merchant's team is emailed): requested (with the
     * review deadline), approved, agent_review or denied.
     */
    public RefundCaseUpdated updated(Instant now) {
        var change = state == State.SELLER_REVIEW ? "requested" : state.code();
        return new RefundCaseUpdated(
                Ids.next(),
                now,
                id,
                merchantId,
                caseNumber,
                change,
                amountCents,
                state == State.SELLER_REVIEW ? contestBy : null);
    }

    /**
     * The refund queue paid it: {@code stripeRefundId} ({@code re_…}) is null when an uncaptured hold was canceled
     * instead; {@code reversal} is the transfer reversal ({@code trr_…}) that took the money back from the merchant's
     * connected account when it had already been released.
     */
    public RefundIssued paid(
            @Nullable String stripeRefundId, @Nullable String reversal, long reversedAmountCents, Instant now) {
        if (state != State.APPROVED) {
            throw new Conflict("refund_not_approved", "Only approved refunds are paid.");
        }
        state = State.PAID;
        paidAt = now;
        stripeRefund = stripeRefundId;
        stripeTransferReversal = reversal;
        reversedCents = reversedAmountCents;
        return new RefundIssued(Ids.next(), now, id, merchantId, caseNumber, escrowId, amountCents, chargedTo.code());
    }

    private void requireSellerReview() {
        if (state != State.SELLER_REVIEW) {
            throw new Conflict("case_closed", CaseMessages.CASE_CLOSED);
        }
    }
}
