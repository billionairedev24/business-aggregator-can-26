package ca.northline.availability.application;

import ca.northline.availability.api.CancellationTerms;
import ca.northline.region.api.CancellationRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CancellationTerms} from the business's saved booking rules — the free window is its "reschedules free until"
 * ({@code reschedule_free_min}), the fee its "Late-cancellation fee" ($ fixed or a share) — clamped to its place's
 * limits ({@link CancellationRules}: market, else province, else configuration); a business without saved rules gets
 * the place's default.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CancellationTermsService implements CancellationTerms {

    private final HoursRepository hours;
    private final CancellationRules rules;
    private final Team team;

    @Override
    public Policy of(String merchantId) {
        var limits = limits(merchantId);
        return hours.rules(merchantId)
                .map(r -> new Policy(
                        Math.min(r.rescheduleFreeMin() / 60, limits.maxWindowHours()),
                        Math.min(r.lateCancelFeeBps() == null ? 0 : r.lateCancelFeeBps(), limits.maxFeeBps()),
                        r.lateCancelFeeCents() == null ? 0 : r.lateCancelFeeCents(),
                        limits.maxFeeBps()))
                .orElseGet(() -> new Policy(limits.windowHours(), limits.feeBps(), 0, limits.maxFeeBps()));
    }

    CancellationRules.Rules limits(String merchantId) {
        var place = team.place(merchantId);
        return rules.of(place.province(), place.marketId());
    }
}
