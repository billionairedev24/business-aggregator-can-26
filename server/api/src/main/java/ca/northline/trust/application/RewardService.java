package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.ActiveRewards;
import ca.northline.trust.api.PointsLedger;
import ca.northline.trust.api.RewardCredits;
import ca.northline.trust.domain.MerchantReward;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-75 provider-funded rewards; S-152 crediting them. Dates are the business's (its time zone, S-134). A change is
 * audit-logged: the business pays for the points. The extra points cost the business the redemption value of a point
 * ({@link PointsProperties#pointsPerDollar()}: 100 → $0.01 each), drawn from the budget under a lock on the reward row.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class RewardService implements ManageReward, ActiveRewards, RewardCredits {

    private final RewardStore rewards;
    private final MerchantPlaces places;
    private final AuditTrail audit;
    private final PointsLedger ledger;
    private final PointsProperties points;
    private final Clock clock;

    @Override
    public Optional<MerchantReward> of(String merchantId) {
        return rewards.find(merchantId);
    }

    @Override
    public RewardStore.Totals totals(String merchantId) {
        return rewards.totals(merchantId);
    }

    @Override
    @Transactional
    public MerchantReward save(ManageReward.Command c) {
        var today = today(c.merchantId());
        var current = rewards.lock(c.merchantId());
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
        var budget = c.budgetCents() != null
                ? c.budgetCents()
                : current.map(MerchantReward::budgetCents).orElse(null);
        var listings = c.listingIds() != null
                ? c.listingIds().stream()
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList()
                : current.map(MerchantReward::listingIds).orElse(List.of());
        var now = clock.instant();
        var wasOn = current.map(MerchantReward::active).orElse(false);
        var started =
                c.active() ? (wasOn ? current.map(MerchantReward::startedAt).orElse(now) : now) : null;
        var reward = new MerchantReward(
                c.merchantId(),
                c.active(),
                multiplier,
                c.label(),
                endsOn,
                now,
                budget,
                current.map(MerchantReward::spentCents).orElse(0L),
                listings,
                started == null ? current.map(MerchantReward::startedAt).orElse(null) : started,
                current.map(MerchantReward::exhaustedAt).orElse(null));
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
                current.map(RewardService::auditView).orElse(null),
                auditView(reward)));
        return rewards.find(c.merchantId()).orElse(reward);
    }

    private static Map<String, Object> auditView(MerchantReward r) {
        var m = new HashMap<String, Object>();
        m.put("active", r.active());
        m.put("multiplier", r.multiplier());
        m.put("endsOn", r.endsOn().toString());
        m.put("budgetCents", r.budgetCents() == null ? "" : String.valueOf(r.budgetCents()));
        m.put("listings", r.listingIds().size());
        return m;
    }

    @Override
    public Optional<Reward> running(String merchantId) {
        var today = today(merchantId);
        return rewards.find(merchantId)
                .filter(r -> r.runningOn(today))
                .map(r -> new Reward(r.multiplier(), r.label(), r.endsOn()));
    }

    @Override
    public List<Running> all() {
        var all = rewards.running();
        var zones = places.ofAll(all.stream().map(MerchantReward::merchantId).toList());
        var now = clock.instant();
        return all.stream()
                .filter(r -> r.runningOn(LocalDate.ofInstant(
                        now, Objects.requireNonNull(zones.get(r.merchantId())).zone())))
                .map(r -> new Running(r.merchantId(), r.multiplier(), r.label(), r.endsOn()))
                .toList();
    }

    // ── crediting (S-152) ─────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Optional<Granted> credit(Sale sale, @Nullable Instant expiresAt) {
        if (sale.basePoints() <= 0) {
            return Optional.empty();
        }
        var reward = rewards.lock(sale.merchantId()).orElse(null);
        if (reward == null) {
            return Optional.empty();
        }
        var zone = places.of(sale.merchantId()).zone();
        var soldOn = LocalDate.ofInstant(sale.soldAt(), zone);
        var started = reward.startedAt();
        if (!reward.runningOn(LocalDate.ofInstant(clock.instant(), zone))
                || started == null
                || sale.soldAt().isBefore(started)
                || soldOn.isAfter(reward.endsOn())
                || !reward.covers(sale.listingId())) {
            return Optional.empty();
        }
        var extra = sale.basePoints() * (reward.multiplier() - 1);
        var perDollar = points.pointsPerDollar();
        var cost = costOf(extra, perDollar);
        var left = reward.leftCents();
        if (cost > left) {
            extra = left * perDollar / 100; // what the rest of the budget buys
            cost = costOf(extra, perDollar);
        }
        if (extra <= 0 || cost <= 0) {
            return Optional.empty();
        }
        var credit = new RewardStore.Credit(
                Ids.next(),
                sale.merchantId(),
                sale.customerId(),
                sale.escrowId(),
                reward.multiplier(),
                extra,
                cost,
                0,
                0,
                clock.instant());
        if (!rewards.insert(credit)) {
            return Optional.empty(); // a retried listener: already credited
        }
        rewards.spend(sale.merchantId(), cost, clock.instant());
        ledger.credit(
                sale.customerId(),
                extra,
                PointsLedger.REWARD,
                sale.escrowId(),
                expiresAt,
                sale.merchantId(),
                reward.multiplier() + "× points");
        return Optional.of(new Granted(credit.id(), reward.multiplier(), extra, cost, cost >= left));
    }

    /** Rounded up: a business never pays less than the points are worth at checkout. */
    private static long costOf(long extraPoints, int pointsPerDollar) {
        return (extraPoints * 100 + pointsPerDollar - 1) / pointsPerDollar;
    }

    @Override
    @Transactional
    public Optional<TakenBack> takeBack(String escrowId, String refundId, long refundedCents, long saleCents) {
        var credit = rewards.creditOf(escrowId).orElse(null);
        if (credit == null || saleCents <= 0 || refundedCents <= 0) {
            return Optional.empty();
        }
        var share = Math.min(1.0, refundedCents / (double) saleCents);
        var pointsBack = Math.min(credit.points() - credit.reversedPoints(), Math.round(credit.points() * share));
        var centsBack = Math.min(credit.cents() - credit.reversedCents(), Math.round(credit.cents() * share));
        if (pointsBack <= 0 && centsBack <= 0) {
            return Optional.empty();
        }
        if (!ledger.takeBack(credit.userId(), pointsBack, PointsLedger.REWARD_REVERSAL, refundId, "Refund")
                && pointsBack > 0) {
            return Optional.empty(); // this refund was already taken back
        }
        rewards.reversed(credit.id(), pointsBack, centsBack);
        rewards.spend(credit.merchantId(), -centsBack, clock.instant());
        return Optional.of(new TakenBack(credit.id(), credit.merchantId(), pointsBack, centsBack));
    }

    private LocalDate today(String merchantId) {
        return LocalDate.now(clock.withZone(places.of(merchantId).zone()));
    }
}
