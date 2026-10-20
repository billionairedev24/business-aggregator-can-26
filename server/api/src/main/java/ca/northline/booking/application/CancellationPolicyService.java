package ca.northline.booking.application;

import ca.northline.booking.api.CancellationPolicies;
import ca.northline.region.api.CancellationRules;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CancellationPolicies} and {@link ManageCancellationPolicy}: the business's own policy, clamped to its region's
 * current limits (a limit lowered later applies to new bookings at once), else the region's default. The region is the
 * business's place ({@link MerchantPlaces}: its market, else its province).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CancellationPolicyService implements CancellationPolicies, ManageCancellationPolicy {

    private final CancellationPolicyStore store;
    private final CancellationRules rules;
    private final MerchantPlaces places;
    private final Clock clock;

    @Override
    public Policy of(String merchantId) {
        return view(merchantId).policy();
    }

    @Override
    public View view(String merchantId) {
        var region = region(merchantId);
        return store.find(merchantId)
                .map(p -> new View(
                        new Policy(
                                Math.min(p.windowHours(), region.maxWindowHours()),
                                Math.min(p.feeBps(), region.maxFeeBps())),
                        true,
                        region))
                .orElseGet(() -> new View(new Policy(region.windowHours(), region.feeBps()), false, region));
    }

    @Override
    @Transactional
    public View set(String merchantId, String actorId, int windowHours, int feeBps) {
        var region = region(merchantId);
        if (windowHours < 0 || windowHours > region.maxWindowHours()) {
            throw RuleViolation.of("windowHours", "range", WINDOW.formatted(region.maxWindowHours()));
        }
        if (feeBps < 0 || feeBps > region.maxFeeBps()) {
            throw RuleViolation.of("feeBps", "range", FEE.formatted(region.maxFeeBps() / 100));
        }
        store.save(merchantId, new Policy(windowHours, feeBps), actorId, clock.instant());
        return view(merchantId);
    }

    @Override
    @Transactional
    public View reset(String merchantId) {
        store.delete(merchantId);
        return view(merchantId);
    }

    private CancellationRules.Rules region(String merchantId) {
        var place = places.of(merchantId);
        return rules.of(place.province(), place.marketId());
    }
}
