package ca.northline.console.application;

import ca.northline.merchants.api.SellerSanctions;
import ca.northline.trust.api.TrustConsequences;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link EnforceTrustRules}: trust decides who ({@link TrustConsequences}), merchants applies ({@link SellerSanctions}). */
@Slf4j
@Service
@RequiredArgsConstructor
class TrustEnforcementService implements EnforceTrustRules {

    static final String RATING_FLOOR = "rating_floor";

    private final TrustConsequences trust;
    private final SellerSanctions sanctions;

    @Override
    @Transactional
    public Result run() {
        var floor = trust.ratingFloor();
        int hidden = 0;
        int restored = 0;
        int suspended = 0;
        for (var below : trust.belowRatingFloor()) {
            var reason = String.format(
                    Locale.ROOT,
                    "Average rating %.2f over %d days is below the floor of %.1f (%d reviews). It shows in search again"
                            + " once the average is back at the floor; you have %d days to recover before a review.",
                    below.average(),
                    floor.days(),
                    floor.rating(),
                    below.reviews(),
                    floor.recoverDays());
            if (sanctions.hideFromSearch(below.merchantId(), RATING_FLOOR, reason)) {
                hidden++;
            }
        }
        for (var id : trust.recovered(sanctions.hiddenFromSearch(RATING_FLOOR))) {
            if (sanctions.restoreSearch(id, RATING_FLOOR, "The average rating is back at or above the floor.")) {
                restored++;
            }
        }
        for (var id : trust.offPlatformAfterWarning(WARNING_WINDOW)) {
            if (sanctions.suspend(
                    id,
                    "Off-platform payment mentioned again after a warning (Northline's rules: warning, then suspension).")) {
                suspended++;
            }
        }
        var result = new Result(hidden, restored, suspended);
        if (hidden + restored + suspended > 0) {
            log.info(
                    "Trust rules enforced: {} hidden from search, {} shown again, {} suspended",
                    hidden,
                    restored,
                    suspended);
        }
        return result;
    }
}
