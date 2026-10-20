package ca.northline.loyalty.application;

import ca.northline.loyalty.application.LoyaltyUseCases.ViewPoints;
import ca.northline.region.api.LoyaltyRules;
import ca.northline.region.api.Markets;
import ca.northline.trust.api.PointsLedger;
import ca.northline.trust.api.PointsWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ViewPoints}: the ledger's summary and activity with the default market's rule (what a dollar earns). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PointsService implements ViewPoints {

    static final int MAX_ACTIVITY = 100;

    private final PointsLedger ledger;
    private final PointsWallet wallet;
    private final LoyaltyRules rules;
    private final Markets markets;
    private final MembershipService plus;

    @Override
    public MyPoints points(String userId, int limit) {
        var summary = ledger.summary(userId);
        var settings = wallet.settings();
        var rule = rules.of(markets.defaultProvince(), null);
        var benefits = plus.of(userId);
        return new MyPoints(
                summary.balance(),
                settings.centsOf(summary.balance()),
                summary.nextExpiryAt(),
                summary.nextExpiryPoints(),
                rule.pointsPerDollar(),
                settings.pointsPerDollar(),
                rule.expiryMonths(),
                benefits.member() ? benefits.pointsMultiplierPct() : 100,
                ledger.activity(userId, Math.max(1, Math.min(limit, MAX_ACTIVITY))));
    }
}
