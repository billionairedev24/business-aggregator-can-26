package ca.northline.loyalty.application;

import ca.northline.identity.api.Households;
import ca.northline.identity.api.SignupDates;
import ca.northline.loyalty.api.PointsCredited;
import ca.northline.loyalty.application.LoyaltyUseCases.Referrals;
import ca.northline.loyalty.domain.Earning;
import ca.northline.loyalty.domain.ReferralCode;
import ca.northline.region.api.LoyaltyRules;
import ca.northline.region.api.Markets;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.PointsLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-152 Invite a neighbour. Everyone gets a random code; a new neighbour uses one, and both get the region's referral
 * points after the invitee's first completed sale of at least the region's minimum. Abuse limits: not your own code,
 * one code per person, new accounts only (created within {@link LoyaltyProperties#newAccountWindow()} and nothing
 * completed yet), not within a household, a few wrong codes an hour, the first sale within the referral window, and at
 * most the region's number of rewarded referrals per referrer in 30 days (a referral over the cap is refused, not
 * delayed).
 */
@Service
@RequiredArgsConstructor
@Transactional
class ReferralService implements Referrals {

    static final Duration CAP_WINDOW = Duration.ofDays(30);

    private final LoyaltyStore store;
    private final LoyaltyRules rules;
    private final Markets markets;
    private final PointsLedger ledger;
    private final Households households;
    private final SignupDates signups;
    private final LoyaltyProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public ReferralView view(String userId) {
        var code = codeFor(userId);
        var rule = rules.of(markets.defaultProvince(), null);
        var totals = store.totals(userId);
        var claimed = store.lockReferral(userId)
                .map(r -> new Claimed(r.code(), r.state(), r.claimedAt(), r.refusedReason()))
                .orElse(null);
        return new ReferralView(
                code,
                props.siteOrigin().replaceAll("/+$", "") + "/?invite=" + code,
                rule.referralPoints(),
                rule.referralMinOrderCents(),
                totals.pending(),
                totals.rewarded(),
                totals.points(),
                claimed,
                claimed == null && isNew(userId, clock.instant()));
    }

    private String codeFor(String userId) {
        var existing = store.codeOf(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int i = 0; i < 5; i++) {
            if (store.insertCode(userId, ReferralCode.next(), clock.instant())) {
                break;
            }
        }
        return store.codeOf(userId).orElseThrow(() -> new IllegalStateException("no referral code for " + userId));
    }

    private boolean isNew(String userId, Instant now) {
        return !store.hasEarned(userId)
                && !signups.signedUpSince(List.of(userId), now.minus(props.newAccountWindow()))
                        .isEmpty();
    }

    /** A refused code still counts as a try: the refusal doesn't roll the try back (nothing else is written first). */
    @Override
    @Transactional(noRollbackFor = RuleViolation.class)
    public ReferralView claim(String userId, @org.jspecify.annotations.Nullable String typed) {
        var now = clock.instant();
        var code = ReferralCode.normalise(typed);
        if (code == null) {
            throw RuleViolation.of("code", "required", ReferralCode.REQUIRED);
        }
        if (store.attemptsSince(userId, now.minus(Duration.ofHours(1))) >= props.claimAttemptsPerHour()) {
            throw RuleViolation.of("code", "rate", ReferralCode.TOO_MANY);
        }
        if (store.lockReferral(userId).isPresent()) {
            throw new Conflict("referral_claimed", ReferralCode.ALREADY);
        }
        var referrer = store.ownerOf(code).orElse(null);
        if (referrer == null) {
            store.attempt(userId, now);
            throw RuleViolation.of("code", "unknown", ReferralCode.UNKNOWN);
        }
        if (referrer.equals(userId)) {
            throw RuleViolation.of("code", "own", ReferralCode.OWN);
        }
        if (!isNew(userId, now)) {
            throw RuleViolation.of("code", "new_only", ReferralCode.NOT_NEW);
        }
        if (households.shared(userId, referrer)) {
            throw RuleViolation.of("code", "household", ReferralCode.SAME_HOUSEHOLD);
        }
        if (!store.insert(new LoyaltyStore.Referral(userId, referrer, code, "pending", now, null, null, 0, null))) {
            throw new Conflict("referral_claimed", ReferralCode.ALREADY);
        }
        return view(userId);
    }

    /** A completed sale of {@code customerId}: their pending referral pays out if it qualifies. */
    void completedSale(String customerId, String escrowId, long amountCents, LoyaltyRules.Rule rule, Instant now) {
        var referral = store.lockReferral(customerId).orElse(null);
        if (referral == null || !referral.state().equals("pending")) {
            return;
        }
        if (now.isAfter(referral.claimedAt().plus(props.referralWindow()))) {
            store.decide(customerId, "refused", "window_passed", escrowId, 0, now);
            return;
        }
        if (rule.referralPoints() <= 0) {
            store.decide(customerId, "refused", "no_points_here", escrowId, 0, now);
            return;
        }
        if (amountCents < rule.referralMinOrderCents()) {
            return; // a later, larger order may still qualify within the window
        }
        var referrer = referral.referrerId();
        if (households.shared(customerId, referrer)) {
            store.decide(customerId, "refused", "same_household", escrowId, 0, now);
            return;
        }
        if (store.rewardedSince(referrer, now.minus(CAP_WINDOW)) >= rule.referralMonthlyCap()) {
            store.decide(customerId, "refused", "referrer_cap", escrowId, 0, now);
            return;
        }
        var points = rule.referralPoints();
        var expires = Earning.expiresAt(now, rule.expiryMonths());
        ledger.credit(customerId, points, PointsLedger.REFERRAL, customerId, expires, null, "Welcome, neighbour");
        ledger.credit(referrer, points, PointsLedger.REFERRAL, customerId, expires, null, "A neighbour you invited");
        store.decide(customerId, "rewarded", null, escrowId, points, now);
        events.publishEvent(new PointsCredited(Ids.next(), now, customerId, customerId, null, points, 0, "referral"));
        events.publishEvent(new PointsCredited(Ids.next(), now, customerId, referrer, null, points, 0, "referral"));
    }

    /** Pending referrals whose window passed are refused. */
    int closeExpired(Instant now) {
        var due = store.pendingClaimedBefore(now.minus(props.referralWindow()), 200);
        due.forEach(invitee -> store.decide(invitee, "refused", "window_passed", null, 0, now));
        return due.size();
    }
}
