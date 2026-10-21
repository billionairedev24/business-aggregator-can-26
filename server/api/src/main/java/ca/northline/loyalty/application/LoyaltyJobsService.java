package ca.northline.loyalty.application;

import ca.northline.loyalty.application.LoyaltyUseCases.LoyaltyJobs;
import ca.northline.trust.api.PointsLedger;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link LoyaltyJobs}: each step in its own transaction (the scheduler calls them one after the other). */
@Service
@RequiredArgsConstructor
class LoyaltyJobsService implements LoyaltyJobs {

    private final PointsLedger ledger;
    private final ReferralService referrals;
    private final MembershipService memberships;
    private final Clock clock;

    @Override
    @Transactional
    public int expirePoints() {
        return ledger.expire(clock.instant(), 500);
    }

    @Override
    @Transactional
    public int closeReferrals() {
        return referrals.closeExpired(clock.instant());
    }

    @Override
    @Transactional
    public int adoptLegacyMemberships() {
        return memberships.adoptLegacy();
    }
}
