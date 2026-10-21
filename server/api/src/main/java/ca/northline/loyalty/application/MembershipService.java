package ca.northline.loyalty.application;

import ca.northline.identity.api.AccountFacts;
import ca.northline.identity.api.Households;
import ca.northline.identity.api.PlusMemberships;
import ca.northline.loyalty.api.LoyaltyFigures;
import ca.northline.loyalty.api.PlusBenefits;
import ca.northline.loyalty.application.LoyaltyUseCases.ManagePlus;
import ca.northline.loyalty.domain.MembershipState;
import ca.northline.payments.api.PlusBilling;
import ca.northline.payments.api.PlusBillingChanged;
import ca.northline.region.api.Markets;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * ({@link PlusBilling} — since S-162 Northline's own: the plan's RFC 5545 rule and price, a PaymentIntent per period,
 * renewals charged by payments' job); what payments decides without the member ({@link PlusBillingChanged}: a renewal
 * paid, refused, lapsed, refunded) moves the membership, an older change than the last one applied changes nothing.
 * Every change is mirrored onto the household ({@link Households#mirrorPlus}), where the account area and {@link #of}
 * read whether benefits apply. A household's first membership starts with the free trial. The tax's province is the
 * payer's default address's, else the region model's default province.
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
    private final AccountFacts accounts;
    private final Markets markets;
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
        var row = plus == null
                ? store.ofPayer(userId).orElse(null) // e.g. a first payment waiting for the bank
                : store.membership(plus.householdId()).orElse(null);
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
                    null,
                    0,
                    null,
                    null,
                    List.of(),
                    null,
                    null);
        }
        var showing = plus != null || row.status().equals("incomplete");
        var payer = row.payerId().equals(userId);
        var subscription = row.subscriptionId();
        var account = showing && payer && subscription != null
                ? billing.account(subscription).orElse(null)
                : null;
        var pending = account == null ? null : account.pending();
        var secret = clientSecret != null ? clientSecret : pending == null ? null : pending.clientSecret();
        return new PlusView(
                showing ? row.status() : "none",
                showing ? row.plan() : null,
                row.provider(),
                showing ? row.startedAt() : null,
                showing ? row.currentPeriodEnd() : null,
                showing && row.cancelAtPeriodEnd(),
                payer,
                plus == null ? 0 : plus.members(),
                props.monthlyPriceCents(),
                props.annualPriceCents(),
                props.trialDays(),
                !showing && trialAvailable,
                benefits,
                account == null ? null : account.nextPlan(),
                account == null ? null : account.nextChargeAt(),
                account == null ? 0 : account.nextChargeCents(),
                account == null ? null : account.card(),
                pending,
                account == null ? List.of() : account.periods(),
                secret,
                clientSecret != null ? publishableKey : pending == null ? null : pending.publishableKey());
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
        var billed = billing.subscribe(
                new PlusBilling.Join(userId, household, props.plan(chosen), trial, province(userId), idempotencyKey));
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
                now);
        save(state, now);
        return answer(userId, state, billed);
    }

    private String province(String userId) {
        var home = accounts.of(userId).defaultProvince();
        var province = home != null ? home : markets.defaultProvince();
        if (province == null) {
            throw new Conflict("address_required", PlusBilling.NO_ADDRESS);
        }
        return province;
    }

    @Override
    public PlusView changePlan(String userId, @Nullable String plan, String idempotencyKey) {
        if (!MembershipState.plan(plan)) {
            throw RuleViolation.of("plan", "allowed", MembershipState.PLAN_REQUIRED);
        }
        var m = payerMembership(userId);
        var chosen = Objects.requireNonNull(plan);
        var waiting = billing.account(subscription(m))
                .map(PlusBilling.Account::nextPlan)
                .orElse(null);
        if (m.plan().equals(chosen) && waiting == null) {
            throw RuleViolation.of("plan", "same", MembershipState.SAME_PLAN);
        }
        return apply(userId, m, billing.changePlan(subscription(m), props.plan(chosen)));
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
        return apply(userId, m, billing.cancelAtPeriodEnd(subscription(m), true));
    }

    @Override
    public PlusView resume(String userId, String idempotencyKey) {
        var m = payerMembership(userId);
        if (!m.cancelAtPeriodEnd()) {
            return view(userId);
        }
        return apply(userId, m, billing.cancelAtPeriodEnd(subscription(m), false));
    }

    @Override
    public PlusView useCard(String userId, @Nullable String paymentMethodId) {
        var m = payerMembership(userId);
        return apply(userId, m, billing.useCard(subscription(m), userId, paymentMethodId));
    }

    @Override
    public PlusView confirm(String userId) {
        var plus = memberships.of(userId).orElse(null);
        var household = plus == null
                ? store.ofPayer(userId).map(MembershipState::householdId).orElse(null)
                : plus.householdId();
        var m = household == null ? null : store.lockMembership(household).orElse(null);
        if (m == null || m.subscriptionId() == null || m.status().equals("canceled")) {
            throw new Conflict("plus_none", MembershipState.NONE);
        }
        if (!m.payerId().equals(userId)) {
            throw new Conflict("plus_not_payer", MembershipState.NOT_PAYER);
        }
        return apply(userId, m, billing.confirm(subscription(m)));
    }

    private PlusView apply(String userId, MembershipState m, PlusBilling.Billed billed) {
        var now = clock.instant();
        var next = withBilled(m, billed, now, now);
        save(next, now);
        return answer(userId, next, billed);
    }

    private PlusView answer(String userId, MembershipState state, PlusBilling.Billed billed) {
        return view(userId, memberships.of(userId).orElse(null), state, billed.clientSecret(), billed.publishableKey());
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

    private static String subscription(MembershipState m) {
        return Objects.requireNonNull(m.subscriptionId());
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

    /** Benefits last past the period while a refused renewal is retried: at least a day past the last retry. */
    private Duration grace() {
        var configured = Duration.ofDays(props.graceDays());
        var retries = billing.retryWindow().plusDays(1);
        return configured.compareTo(retries) >= 0 ? configured : retries;
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

    // ── payments says ───────────────────────────────────────────────────────────────────────────────────────────

    @ApplicationModuleListener
    void on(PlusBillingChanged e) {
        var m = store.bySubscription(e.aggregateId()).orElse(null);
        if (m == null) {
            log.warn("Plus billing {} is unknown here (household {}); ignored", e.aggregateId(), e.householdId());
            return;
        }
        var last = m.lastEventAt();
        if (last != null && e.occurredAt().isBefore(last)) {
            return; // older than the change applied
        }
        var billed = new PlusBilling.Billed(
                e.aggregateId(), e.status(), e.plan(), e.currentPeriodEnd(), e.cancelAtPeriodEnd(), null, null);
        save(withBilled(m, billed, e.occurredAt(), clock.instant()), clock.instant());
    }

    /**
     * S-162: running S-152 memberships (Stripe Billing or the local fake, {@code sub_…}) move to Northline's billing:
     * the next period starts at the end of the current one, on the plan's rule and price. (Stripe Billing subscriptions
     * left in a Stripe account are cancelled there by hand: docs/runbooks/stripe.md § 11.)
     */
    int adoptLegacy() {
        var n = 0;
        for (var m : store.legacy(200)) {
            var end = Objects.requireNonNullElse(m.currentPeriodEnd(), clock.instant());
            var billed = billing.adopt(
                    m.payerId(),
                    m.householdId(),
                    props.plan(m.plan()),
                    end,
                    province(m.payerId()),
                    m.status().equals("trialing"));
            var now = clock.instant();
            save(withBilled(m, billed, now, now), now);
            n++;
        }
        return n;
    }
}
