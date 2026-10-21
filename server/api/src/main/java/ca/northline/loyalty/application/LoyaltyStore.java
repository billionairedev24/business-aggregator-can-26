package ca.northline.loyalty.application;

import ca.northline.loyalty.domain.MembershipState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-152): schema {@code loyalty}. */
public interface LoyaltyStore {

    // ── earnings ────────────────────────────────────────────────────────────────────────────────────────────────

    /** What one released escrow earned. */
    record EarningRow(
            String escrowId,
            String customerId,
            String merchantId,
            String kind,
            String refType,
            String refId,
            long amountCents,
            @Nullable String ruleId,
            int pointsPerDollar,
            int plusMultiplierPct,
            long points,
            long rewardPoints,
            long reversedCents,
            long reversedPoints,
            @Nullable Instant expiresAt,
            Instant createdAt,
            List<String> excludedRefundIds) {

        public EarningRow {
            excludedRefundIds = List.copyOf(excludedRefundIds);
        }
    }

    /** @return false when the escrow already earned (a retried listener) */
    boolean insert(EarningRow earning);

    /** The escrow's earning, locked for a refund's reversal. */
    Optional<EarningRow> lockEarning(String escrowId);

    void reversed(String escrowId, long cents, long points);

    void rewardPoints(String escrowId, long points);

    boolean hasEarned(String customerId);

    // ── referrals ───────────────────────────────────────────────────────────────────────────────────────────────

    Optional<String> codeOf(String userId);

    /** @return false when the code is taken (try another) or the person has one */
    boolean insertCode(String userId, String code, Instant at);

    Optional<String> ownerOf(String code);

    /**
     * @param state {@code pending | rewarded | refused}
     */
    record Referral(
            String inviteeId,
            String referrerId,
            String code,
            String state,
            Instant claimedAt,
            @Nullable Instant decidedAt,
            @Nullable String escrowId,
            long points,
            @Nullable String refusedReason) {}

    /** @return false when the invitee already claimed one */
    boolean insert(Referral referral);

    Optional<Referral> lockReferral(String inviteeId);

    void decide(
            String inviteeId,
            String state,
            @Nullable String reason,
            @Nullable String escrowId,
            long points,
            Instant at);

    /** Referrals the referrer was rewarded for since {@code since}. */
    long rewardedSince(String referrerId, Instant since);

    /** Pending and rewarded referrals and the points the referrer earned from them. */
    record ReferralTotals(long pending, long rewarded, long points) {}

    ReferralTotals totals(String referrerId);

    /** Pending referrals claimed before {@code before} (their window passed). */
    List<String> pendingClaimedBefore(Instant before, int limit);

    long attemptsSince(String userId, Instant since);

    void attempt(String userId, Instant at);

    // ── Plus ────────────────────────────────────────────────────────────────────────────────────────────────────

    Optional<MembershipState> membership(String householdId);

    Optional<MembershipState> lockMembership(String householdId);

    Optional<MembershipState> bySubscription(String subscriptionId);

    /** S-162: the latest membership the person pays for (or started), whatever its status. */
    Optional<MembershipState> ofPayer(String userId);

    void save(MembershipState membership, Instant at);

    /**
     * S-162: running memberships still billed the S-152 way (a Stripe Billing or local-fake subscription,
     * {@code sub_…}), to move to Northline's own billing.
     */
    List<MembershipState> legacy(int limit);

    /** @param members households whose membership runs; {@code trialing} of them in their trial */
    record PlusCounts(long members, long trialing) {}

    PlusCounts counts();

    long pendingReferrals();
}
