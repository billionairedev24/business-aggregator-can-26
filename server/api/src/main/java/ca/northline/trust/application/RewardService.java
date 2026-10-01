package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.ActiveRewards;
import ca.northline.trust.domain.MerchantReward;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-75 provider-funded rewards. Dates are the business's (its time zone, S-134). A change is audit-logged: the business
 * pays for the points. Crediting points waits for the earning rules (DECISIONS S-58): nothing credits points yet.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class RewardService implements ManageReward, ActiveRewards {

    private final RewardStore rewards;
    private final MerchantPlaces places;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public Optional<MerchantReward> of(String merchantId) {
        return rewards.find(merchantId);
    }

    @Override
    @Transactional
    public MerchantReward save(ManageReward.Command c) {
        var today = today(c.merchantId());
        var current = rewards.find(c.merchantId());
        var endsOn = c.endsOn() != null
                ? c.endsOn()
                : current.map(MerchantReward::endsOn).orElse(null);
        if (endsOn == null) {
            if (c.active()) {
                throw RuleViolation.of("endsOn", "required", MerchantReward.ENDS_REQUIRED);
            }
            endsOn = today;
        }
        // switching off keeps the last terms, so switching on again starts from them
        var multiplier = c.active() || c.multiplier() == 2 || c.multiplier() == 3
                ? c.multiplier()
                : current.map(MerchantReward::multiplier).orElse(2);
        var reward = new MerchantReward(c.merchantId(), c.active(), multiplier, c.label(), endsOn, clock.instant());
        var problems = reward.validate(today);
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        rewards.save(reward, c.actorId());
        audit.record(new AuditTrail.Entry(
                c.merchantId(),
                c.actorId(),
                c.role(),
                reward.active() ? "reward.started" : "reward.stopped",
                "merchant_reward",
                c.merchantId(),
                current.map(r -> Map.of(
                                "active",
                                r.active(),
                                "multiplier",
                                r.multiplier(),
                                "endsOn",
                                r.endsOn().toString()))
                        .orElse(null),
                Map.of(
                        "active",
                        reward.active(),
                        "multiplier",
                        reward.multiplier(),
                        "endsOn",
                        reward.endsOn().toString())));
        return reward;
    }

    @Override
    public Optional<Reward> running(String merchantId) {
        var today = today(merchantId);
        return rewards.find(merchantId)
                .filter(r -> r.runningOn(today))
                .map(r -> new Reward(r.multiplier(), r.label(), r.endsOn()));
    }

    private LocalDate today(String merchantId) {
        return LocalDate.now(clock.withZone(places.of(merchantId).zone()));
    }
}
