package ca.northline.loyalty.application;

import ca.northline.identity.api.Households;
import ca.northline.identity.api.PlusMemberships;
import ca.northline.loyalty.api.LoyaltyFigures;
import ca.northline.loyalty.api.PlusBenefits;
import ca.northline.loyalty.application.LoyaltyUseCases.ManagePlus;
import ca.northline.loyalty.domain.MembershipState;
import ca.northline.payments.api.PlusBilling;
import ca.northline.payments.api.PlusSubscriptionUpdated;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-152 Northline Plus: one membership per household, paid by the person who joined. Billing is payments'
 * ({@link PlusBilling}: Stripe Billing or the local fake); the provider's webhooks ({@link PlusSubscriptionUpdated})
 * move the membership, an older event than the last one applied changes nothing. Every change is mirrored onto the
 * household ({@link Households#mirrorPlus}), where the account area and {@link #of} read whether benefits apply. A
 * household's first membership starts with the free trial.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@EnableConfigurationProperties({PlusProperties.class, LoyaltyProperties.class})
class MembershipService implements ManagePlus, PlusBenefits, LoyaltyFigures {

    private final LoyaltyStore store;
    private final Households households;
    private final PlusMemberships memberships;
    private final PlusBilling billing;
    private final PlusProperties props;
    private final Clock clock;

    // ── benefits ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Benefits of(String userId) {
        if (memberships.of(userId).isEmpty()) {
            return Benefits.NONE;
        }
        return new Benefits(true, props.serviceFeeWaived(), props.memberPrices(), props.pointsMultiplierPct());
    }

    private Benefits offered() {
        return new Benefits(false, props.serviceFeeWaived(), props.memberPrices(), props.pointsMultiplierPct());
    }

    @Override
    @Transactional(readOnly = true)
    public Figures figures() {
        var counts = store.counts();
        return new Figures(counts.members(), counts.trialing(), store.pendingReferrals());
    }

    // ── the account ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PlusView view(String userId) {
        var plus = memberships.of(userId).orElse(null);
        var household = plus == null ? null : plus.householdId();
        var row = household == null ? null : store.membership(household).orElse(null);
        return view(userId, plus, row, null, null);
    }

    private PlusView view(
            String userId,
            PlusMemberships.@Nullable Plus plus,
            @Nullable MembershipState row,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {
        var benefits = plus == null ? offered() : of(userId);
        var trialAvailable = props.trialDays() > 0 && (row == null || !row.trialUsed());
        if (row == null) {
            // none, or a free trial from before S-152 (no billing): it runs to its end
            return new PlusView(
                    plus == null ? "none" : "trialing",
                    plus == null ? null : plus.plan(),
                    billing.provider(),
                    plus == null ? null : plus.since(),
                    plus == null ? null : plus.renewsAt(),
                    plus != null,
                    false,
                    plus == null ? 0 : plus.members(),
                    props.monthlyPriceCents(),
                    props.annualPriceCents(),
                    props.trialDays(),
                    plus == null && trialAvailable,
                    benefits,
                    null,
                    null);
        }
        var showing = plus != null || row.status().equals("incomplete");
        return new PlusView(
                showing ? row.status() : "none",
                showing ? row.plan() : null,
                row.provider(),
                showing ? row.startedAt() : null,
                showing ? row.currentPeriodEnd() : null,
                showing && row.cancelAtPeriodEnd(),
                row.payerId().equals(userId),
                plus == null ? 0 : plus.members(),
                props.monthlyPriceCents(),
                props.annualPriceCents(),
                props.trialDays(),
                !showing && trialAvailable,
                benefits,
                clientSecret,
                publishableKey);
    }

    @Override
    public PlusView join(String userId, @Nullable String plan, String idempotencyKey) {
        if (!MembershipState.plan(plan)) {
            throw RuleViolation.of("plan", "allowed", MembershipState.PLAN_REQUIRED);
        }
        var chosen = Objects.requireNonNull(plan);
        if (memberships.of(userId).isPresent()) {
            throw new Conflict("plus_active", MembershipState.ALREADY);
        }
        var household = households.ensure(userId);
        var now = clock.instant();
        var current = store.lockMembership(household).orElse(null);
        if (current != null && current.running() && current.benefitsAt(now, grace())) {
            throw new Conflict("plus_active", MembershipState.ALREADY);
        }
        var trial = current == null || !current.trialUsed() ? props.trialDays() : 0;
        var billed = billing.subscribe(userId, household, chosen, props.priceCents(chosen), trial, idempotencyKey);
        var state = new MembershipState(
                household,
                userId,
                billed.plan(),
                billed.status(),
                billing.provider(),
                billed.subscriptionId(),
                billed.currentPeriodEnd(),
                billed.cancelAtPeriodEnd(),
                (current != null && current.trialUsed()) || trial > 0,
                now,
                null,
                null);
        save(state, now);
        return view(userId, memberships.of(userId).orElse(null), state, billed.clientSecret(), billed.publishableKey());
    }

    @Override
    public PlusView changePlan(String userId, @Nullable String plan, String idempotencyKey) {
        if (!MembershipState.plan(plan)) {
            throw RuleViolation.of("plan", "allowed", MembershipState.PLAN_REQUIRED);
        }
        var m = payerMembership(userId);
        if (m.plan().equals(plan)) {
            throw RuleViolation.of("plan", "same", MembershipState.SAME_PLAN);
        }
        var chosen = Objects.requireNonNull(plan);
        var billed = billing.changePlan(current(m), chosen, props.priceCents(chosen), idempotencyKey);
        var next = withBilled(m, billed, null, clock.instant());
        save(next, clock.instant());
        return view(userId, memberships.of(userId).orElse(null), next, null, null);
    }

    @Override
    public PlusView cancel(String userId, String idempotencyKey) {
        var plus = memberships.of(userId).orElse(null);
        var household = plus == null ? null : plus.householdId();
        if (household != null && store.membership(household).isEmpty()) {
            // a free trial from before S-152: nothing is billed, it simply ends now
            households.mirrorPlus(household, "none", null, null, null);
            return view(userId);
        }
        var m = payerMembership(userId);
        if (m.cancelAtPeriodEnd()) {
            return view(userId);
        }
        var billed = billing.cancelAtPeriodEnd(current(m), true, idempotencyKey);
        var next = withBilled(m, billed, null, clock.instant());
        save(next, clock.instant());
        return view(userId, memberships.of(userId).orElse(null), next, null, null);
    }

    @Override
    public PlusView resume(String userId, String idempotencyKey) {
        var m = payerMembership(userId);
        if (!m.cancelAtPeriodEnd()) {
            return view(userId);
        }
        var billed = billing.cancelAtPeriodEnd(current(m), false, idempotencyKey);
        var next = withBilled(m, billed, null, clock.instant());
        save(next, clock.instant());
        return view(userId, memberships.of(userId).orElse(null), next, null, null);
    }

    private MembershipState payerMembership(String userId) {
        var plus = memberships.of(userId).orElse(null);
        var m = plus == null ? null : store.lockMembership(plus.householdId()).orElse(null);
        if (m == null || !m.running() || m.subscriptionId() == null) {
            throw new Conflict("plus_none", MembershipState.NONE);
        }
        if (!m.payerId().equals(userId)) {
            throw new Conflict("plus_not_payer", MembershipState.NOT_PAYER);
        }
        return m;
    }

    private static PlusBilling.Current current(MembershipState m) {
        return new PlusBilling.Current(
                Objects.requireNonNull(m.subscriptionId()),
                m.householdId(),
                m.plan(),
                m.status(),
                m.currentPeriodEnd(),
                m.cancelAtPeriodEnd());
    }

    private static MembershipState withBilled(
            MembershipState m, PlusBilling.Billed b, @Nullable Instant eventAt, Instant now) {
        return new MembershipState(
                m.householdId(),
                m.payerId(),
                b.plan(),
                b.status(),
                m.provider(),
                b.subscriptionId(),
                b.currentPeriodEnd() == null ? m.currentPeriodEnd() : b.currentPeriodEnd(),
                b.cancelAtPeriodEnd(),
                m.trialUsed(),
                m.startedAt(),
                b.status().equals("canceled") ? Objects.requireNonNullElse(m.canceledAt(), now) : null,
                eventAt == null ? m.lastEventAt() : eventAt);
    }

    private Duration grace() {
        return Duration.ofDays(props.graceDays());
    }

    /** Stores the membership and mirrors it onto the household. */
    private void save(MembershipState m, Instant now) {
        store.save(m, now);
        var until = m.running() ? m.benefitsUntil(grace()) : null;
        if (until != null && until.isAfter(now)) {
            households.mirrorPlus(m.householdId(), m.plan(), m.startedAt(), until, m.subscriptionId());
        } else {
            households.mirrorPlus(m.householdId(), "none", null, null, m.subscriptionId());
        }
    }

    // ── the provider says ───────────────────────────────────────────────────────────────────────────────────────

    @ApplicationModuleListener
    void on(PlusSubscriptionUpdated e) {
        var m = store.bySubscription(e.aggregateId()).orElse(null);
        if (m == null) {
            log.warn("Plus subscription {} is unknown here (household {}); ignored", e.aggregateId(), e.householdId());
            return;
        }
        var last = m.lastEventAt();
        if (last != null && e.occurredAt().isBefore(last)) {
            return; // an older event than the one applied
        }
        var billed = new PlusBilling.Billed(
                e.aggregateId(),
                e.status(),
                e.plan() == null ? m.plan() : e.plan(),
                e.currentPeriodEnd(),
                e.cancelAtPeriodEnd(),
                null,
                null);
        save(withBilled(m, billed, e.occurredAt(), clock.instant()), clock.instant());
    }

    /** The local fake's renewals (Stripe renews by itself and says so by webhook). */
    int renewLocal(Instant now) {
        var due = store.localDue(now, 200);
        var n = 0;
        for (var m : due) {
            var renewed = billing.renewIfDue(current(m), props.priceCents(m.plan()), now);
            if (renewed.isPresent()) {
                save(withBilled(m, renewed.get(), null, now), now);
                n++;
            }
        }
        return n;
    }
}
