package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.api.PlusBilling;
import ca.northline.payments.api.PlusBillingChanged;
import ca.northline.payments.api.PlusPaymentUpdated;
import ca.northline.payments.application.PaymentGateway.Charged;
import ca.northline.payments.domain.BillingRule;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.payments.domain.PlusPeriod;
import ca.northline.payments.domain.PlusSchedule;
import ca.northline.payments.domain.RenewalRetries;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.ProviderUnavailable;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-162: Northline bills Plus itself — {@link PlusBilling} for the loyalty module and {@link MembershipBilling} for the
 * jobs, webhooks and console. A membership is a {@link PlusSchedule}: the plan's RFC 5545 rule anchored to the first
 * paid instant ({@link BillingRule}), its price, the card. Each {@link PlusPeriod} is charged once as an
 * automatic-capture PaymentIntent on the payer's saved card (S-59): tax from the {@link TaxGateway} (the payer's
 * province, a service), the Stripe idempotency key per membership and period, the attempt's number claimed in the
 * database before Stripe is called. Joining charges the first period on-session (3-D Secure through the returned
 * client secret); renewals are charged off-session by the job, a refusal is tried again on {@link RenewalRetries}
 * (benefits continue meanwhile — the membership is {@code past_due}), {@code authentication_required} asks the member
 * to confirm (email and push, {@link PlusPaymentUpdated}), and after the last retry the membership lapses. Paid periods
 * go to the ledger as revenue and tax ({@code plus_invoice}); a refund gives both back. Cancelling stops the next
 * renewal; a plan change waits for it.
 */
@Slf4j
@Service
@Transactional
@EnableConfigurationProperties(PlusBillingSettings.class)
class NorthlineMembershipBilling implements PlusBilling, MembershipBilling {

    static final String NOT_REFUNDABLE = "Only a paid Plus period can be refunded.";
    private static final Duration PROCESSING_CHECK = Duration.ofMinutes(15);

    private final PlusScheduleStore store;
    private final PaymentGateway gateway;
    private final TaxGateway taxes;
    private final LedgerRepository ledger;
    private final EscrowRepository customers;
    private final PaymentMethods.SavedCardStore cards;
    private final PaymentSettings settings;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;
    private final PlusBillingSettings props;
    private final RenewalRetries retries;
    private final TransactionTemplate perPeriod;

    NorthlineMembershipBilling(
            PlusScheduleStore store,
            PaymentGateway gateway,
            TaxGateway taxes,
            LedgerRepository ledger,
            EscrowRepository customers,
            PaymentMethods.SavedCardStore cards,
            PaymentSettings settings,
            ApplicationEventPublisher events,
            AuditTrail audit,
            Clock clock,
            PlusBillingSettings props,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.gateway = gateway;
        this.taxes = taxes;
        this.ledger = ledger;
        this.customers = customers;
        this.cards = cards;
        this.settings = settings;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
        this.retries = RenewalRetries.days(props.retryDays());
        this.perPeriod = new TransactionTemplate(transactionManager);
        this.perPeriod.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String provider() {
        return "stripe".equals(settings.provider()) ? "stripe" : "local";
    }

    @Override
    public Duration retryWindow() {
        return retries.last();
    }

    // ── the member's requests (loyalty) ─────────────────────────────────────────────────────────────────────────

    @Override
    public Billed subscribe(Join join) {
        var now = clock.instant();
        if (customers.stripeCustomer(join.userId()).isEmpty() || card(join.userId(), null) == null) {
            throw new Conflict("card_required", NO_CARD);
        }
        var province = province(join.province());
        for (var old : store.ofHousehold(join.householdId())) {
            if (!old.ended()) { // a first payment never confirmed, or a membership loyalty no longer counts
                end(old, "unconfirmed", now, false);
            }
        }
        var plan = join.plan();
        var trial = join.trialDays() > 0;
        var start = trial ? now.plus(Duration.ofDays(join.trialDays())) : now;
        var rule = BillingRule.anchored(plan.rrule(), start);
        var schedule = new PlusSchedule(
                "plus_" + Ids.next(),
                join.householdId(),
                join.userId(),
                plan.plan(),
                rule.rrule(),
                rule.dtstart(),
                plan.priceCents(),
                province.name(),
                null,
                null,
                null,
                null,
                trial ? PlusSchedule.TRIALING : PlusSchedule.INCOMPLETE,
                rule.dtstart(),
                false,
                null,
                null);
        store.insert(schedule, now);
        var first = PlusPeriod.scheduled(
                Ids.next(),
                schedule.id(),
                trial ? 1 : 0,
                rule.dtstart(),
                rule.after(rule.dtstart()),
                plan.plan(),
                plan.priceCents());
        if (trial) {
            store.insert(
                    new PlusPeriod(
                            Ids.next(),
                            schedule.id(),
                            0,
                            now,
                            rule.dtstart(),
                            plan.plan(),
                            PlusPeriod.FREE,
                            0,
                            0,
                            null,
                            null,
                            null,
                            null,
                            0,
                            null,
                            null,
                            null,
                            null,
                            null),
                    now);
            store.insert(first, now);
            return billed(schedule, null);
        }
        store.insert(first, now);
        var key = StripeIdempotencyKeys.fromClient("plus-join", join.householdId(), join.clientKey());
        var attempt = attempt(schedule, first, false, key, now);
        return switch (attempt.outcome()) {
            case Charged.Paid paid -> billed(paid(schedule, attempt.period(), paid, now, false), null);
            case Charged.ActionRequired action -> {
                store.update(
                        attempt.period()
                                .withState(PlusPeriod.REQUIRES_ACTION)
                                .withFailure("authentication_required")
                                .withNextAttemptAt(now.plus(props.unconfirmed())),
                        now);
                yield billed(schedule, action.clientSecret());
            }
            case Charged.Processing _ -> {
                store.update(
                        attempt.period()
                                .withState(PlusPeriod.REQUIRES_ACTION)
                                .withFailure("processing")
                                .withNextAttemptAt(now.plus(PROCESSING_CHECK)),
                        now);
                yield billed(schedule, null);
            }
            case Charged.Declined _ -> throw new Conflict("card_declined", DECLINED);
        };
    }

    @Override
    public Billed changePlan(String subscriptionId, Plan next) {
        var s = running(subscriptionId);
        var rule = BillingRule.planRule(next.rrule());
        var changed = next.plan().equals(s.plan())
                ? s.withNextPlan(null).withNextRrule(null).withNextPriceCents(null)
                : s.withNextPlan(next.plan()).withNextRrule(rule).withNextPriceCents(next.priceCents());
        store.update(changed, clock.instant());
        return billed(changed, null);
    }

    @Override
    public Billed cancelAtPeriodEnd(String subscriptionId, boolean cancel) {
        var s = running(subscriptionId);
        var now = clock.instant();
        if (cancel && PlusSchedule.PAST_DUE.equals(s.status())) {
            // nothing is paid for past the period's end: a renewal being retried stops, and so does the membership
            return billed(end(s, "canceled", now, false), null);
        }
        var changed = s.withCancelAtPeriodEnd(cancel);
        store.update(changed, now);
        return billed(changed, null);
    }

    @Override
    public Billed useCard(String subscriptionId, String payerId, @Nullable String paymentMethod) {
        var s = running(subscriptionId);
        if (paymentMethod != null && cards.cards(payerId).stream().noneMatch(c -> c.id().equals(paymentMethod))) {
            throw RuleViolation.of("paymentMethodId", "allowed", NOT_YOUR_CARD);
        }
        var now = clock.instant();
        var changed = s.withPaymentMethod(paymentMethod);
        store.update(changed, now);
        store.open(s.id())
                .filter(p -> !PlusPeriod.SCHEDULED.equals(p.state()) && !PlusSchedule.INCOMPLETE.equals(s.status()))
                .ifPresent(p -> store.update(p.withNextAttemptAt(now), now)); // the new card on the job's next run
        return billed(changed, null);
    }

    @Override
    public Billed confirm(String subscriptionId) {
        var s = store.lockSchedule(subscriptionId).orElseThrow(() -> new NotFound("plus_membership", subscriptionId));
        var open = store.open(s.id()).filter(p -> p.paymentIntent() != null).orElse(null);
        if (s.ended() || open == null) {
            throw new Conflict("plus_nothing_to_confirm", NOTHING_TO_CONFIRM);
        }
        var period = store.lockPeriod(open.id()).orElseThrow();
        var state = gateway.chargeState(Objects.requireNonNull(period.paymentIntent()));
        var now = clock.instant();
        if (state instanceof Charged.Paid paid) {
            return billed(paid(s, period, paid, now, true), null);
        }
        return billed(s, state instanceof Charged.ActionRequired a ? a.clientSecret() : null);
    }

    @Override
    public Billed adopt(
            String payerId, String householdId, Plan plan, Instant periodEnd, String province, boolean trialing) {
        var now = clock.instant();
        var start = periodEnd.isBefore(now) ? now : periodEnd;
        var rule = BillingRule.anchored(plan.rrule(), start);
        var schedule = new PlusSchedule(
                "plus_" + Ids.next(),
                householdId,
                payerId,
                plan.plan(),
                rule.rrule(),
                rule.dtstart(),
                plan.priceCents(),
                province(province).name(),
                null,
                null,
                null,
                null,
                trialing ? PlusSchedule.TRIALING : PlusSchedule.ACTIVE,
                rule.dtstart(),
                false,
                null,
                null);
        store.insert(schedule, now);
        store.insert(
                PlusPeriod.scheduled(
                        Ids.next(),
                        schedule.id(),
                        0,
                        rule.dtstart(),
                        rule.after(rule.dtstart()),
                        plan.plan(),
                        plan.priceCents()),
                now);
        return billed(schedule, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> account(String subscriptionId) {
        return store.schedule(subscriptionId).map(s -> {
            var open = store.open(s.id()).orElse(null);
            var periods = store.periods(s.id(), 12).stream()
                    .map(NorthlineMembershipBilling::period)
                    .toList();
            @Nullable Instant nextChargeAt = null;
            if (open != null && !s.ended() && !s.cancelAtPeriodEnd()) {
                nextChargeAt = PlusPeriod.SCHEDULED.equals(open.state()) ? open.startsAt() : open.nextAttemptAt();
            }
            var price = Objects.requireNonNullElse(s.nextPriceCents(), s.priceCents());
            return new Account(s.id(), s.nextPlan(), nextChargeAt, price, cardView(s), pending(s, open), periods);
        });
    }

    private @Nullable Pending pending(PlusSchedule s, @Nullable PlusPeriod open) {
        if (open == null || s.ended() || PlusPeriod.SCHEDULED.equals(open.state())) {
            return null;
        }
        @Nullable String secret = null;
        var pi = open.paymentIntent();
        if (PlusPeriod.REQUIRES_ACTION.equals(open.state()) && pi != null) {
            try {
                if (gateway.chargeState(pi) instanceof Charged.ActionRequired action) {
                    secret = action.clientSecret();
                }
            } catch (ProviderUnavailable e) {
                log.warn("Plus period {}: Stripe unavailable reading {}", open.id(), pi);
            }
        }
        var incomplete = PlusSchedule.INCOMPLETE.equals(s.status());
        var lapses = incomplete
                ? Objects.requireNonNullElse(open.nextAttemptAt(), open.startsAt())
                : open.startsAt().plus(retries.last());
        return new Pending(
                open.id(),
                Objects.requireNonNullElse(open.failure(), "declined"),
                open.totalCents(),
                incomplete ? null : open.nextAttemptAt(),
                lapses,
                secret,
                secret == null ? null : settings.publishableKey());
    }

    private @Nullable Card cardView(PlusSchedule s) {
        var all = cards.cards(s.payerId());
        var chosen = s.paymentMethod();
        var card = all.stream()
                .filter(c -> c.id().equals(chosen))
                .findFirst()
                .or(() ->
                        all.stream().filter(PaymentMethods.SavedCard::isDefault).findFirst())
                .or(() -> all.stream().findFirst())
                .orElse(null);
        return card == null
                ? null
                : new Card(card.id(), card.brand(), card.last4(), card.id().equals(chosen));
    }

    // ── jobs, webhooks, console (MembershipBilling) ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int chargeDue(Instant now) {
        var changed = 0;
        for (var id : store.due(now, props.batch())) {
            try {
                if (Boolean.TRUE.equals(perPeriod.execute(_ -> renew(id, now)))) {
                    changed++;
                }
            } catch (ProviderUnavailable e) {
                log.warn("Plus period {}: Stripe unavailable, tried again on the next run", id);
            } catch (RuntimeException e) {
                log.error("Plus period {} failed; tried again on the next run", id, e);
            }
        }
        return changed;
    }

    /** One due period, locked so a concurrent run moves on. */
    private boolean renew(String periodId, Instant now) {
        var p = store.lockPeriodIfFree(periodId).orElse(null);
        var next = p == null ? null : p.nextAttemptAt();
        if (p == null || !p.open() || next == null || next.isAfter(now)) {
            return false;
        }
        var s = store.lockSchedule(p.scheduleId()).orElseThrow();
        if (s.ended()) {
            store.update(p.withState(PlusPeriod.VOID).withNextAttemptAt(null), now);
            return true;
        }
        var pi = p.paymentIntent();
        if (pi != null) { // the member may have confirmed it meanwhile, or the network finished processing
            var state = gateway.chargeState(pi);
            if (state instanceof Charged.Paid paid) {
                paid(s, p, paid, now, true);
                return true;
            }
        }
        if (PlusSchedule.INCOMPLETE.equals(s.status())) { // the first payment, never confirmed
            store.update(p.withState(PlusPeriod.VOID).withNextAttemptAt(null), now);
            end(s, "unconfirmed", now, true);
            return true;
        }
        if (PlusPeriod.SCHEDULED.equals(p.state())) {
            if (s.cancelAtPeriodEnd()) {
                store.update(p.withState(PlusPeriod.VOID).withNextAttemptAt(null), now);
                end(s, "canceled", now, true);
                return true;
            }
            if (s.nextPlan() != null) { // the plan change waited for this renewal: the new rule starts here
                s = s.switchedAt(p.startsAt());
                p = p.withPlan(s.plan())
                        .withAmountCents(s.priceCents())
                        .withEndsAt(s.rule().after(p.startsAt()));
            }
        }
        var key = StripeIdempotencyKeys.of("plus", s.id(), p.id());
        var attempt = attempt(s, p, true, key, now);
        var period = attempt.period();
        switch (attempt.outcome()) {
            case Charged.Paid paid -> paid(s, period, paid, now, true);
            case Charged.ActionRequired _ -> refused(s, period, "authentication_required", now);
            case Charged.Processing _ ->
                store.update(period.withFailure("processing").withNextAttemptAt(now.plus(PROCESSING_CHECK)), now);
            case Charged.Declined declined -> refused(s, period, declined.code(), now);
        }
        return true;
    }

    private record Attempt(PlusPeriod period, Charged outcome) {}

    /**
     * Claims the next attempt, prices the tax on the first one, and charges the card (a new PaymentIntent with
     * {@code key}, or the period's own again).
     */
    private Attempt attempt(PlusSchedule s, PlusPeriod p, boolean offSession, String key, Instant now) {
        var n = p.attempts() + 1;
        if (!store.claimAttempt(p.id(), n, now, offSession)) {
            throw new Conflict("plus_attempt_taken", "This Plus payment is being charged already.");
        }
        var period = p.withAttempts(n);
        if (period.taxCalculation() == null) {
            var province = province(s.province());
            var tax = taxes.calculate(new TaxGateway.Calculate(
                    "plus_" + p.id(),
                    EscrowKind.SERVICE,
                    province,
                    null,
                    period.amountCents(),
                    StripeIdempotencyKeys.of("plus-tax", p.id())));
            period = period.withTaxCents(tax.taxCents())
                    .withTaxCalculation(tax.calculation())
                    .withProvince(province.name());
        }
        var customer = customers.stripeCustomer(s.payerId()).orElse(null);
        var card = card(s.payerId(), s.paymentMethod());
        Charged outcome;
        if (customer == null || card == null) {
            outcome = new Charged.Declined(period.paymentIntent(), "no_card");
        } else if (period.paymentIntent() == null) {
            outcome = gateway.charge(new PaymentGateway.Charge(
                    period.totalCents(), customer, card, offSession, metadata(s, period), key));
        } else {
            outcome = gateway.chargeAgain(
                    period.paymentIntent(),
                    card,
                    offSession,
                    StripeIdempotencyKeys.of("plus-retry", p.id(), String.valueOf(n)));
        }
        var pi = switch (outcome) {
            case Charged.Paid c -> c.paymentIntent();
            case Charged.ActionRequired c -> c.paymentIntent();
            case Charged.Processing c -> c.paymentIntent();
            case Charged.Declined c -> c.paymentIntent();
        };
        store.attemptOutcome(
                p.id(),
                n,
                switch (outcome) {
                    case Charged.Paid _ -> "paid";
                    case Charged.ActionRequired _ -> "authentication_required";
                    case Charged.Processing _ -> "processing";
                    case Charged.Declined _ -> "declined";
                },
                outcome instanceof Charged.Declined d ? d.code() : null,
                pi);
        if (pi != null) {
            period = period.withPaymentIntent(pi);
        }
        store.update(period, now);
        return new Attempt(period, outcome);
    }

    private static Map<String, String> metadata(PlusSchedule s, PlusPeriod p) {
        return Map.of(
                "northline_kind", "plus",
                "northline_plus_membership", s.id(),
                "northline_plus_period", p.id(),
                "northline_household", s.householdId());
    }

    /** The card Plus charges: the one chosen for it while saved, else the default, else the newest. */
    private @Nullable String card(String payerId, @Nullable String chosen) {
        var all = cards.cards(payerId);
        return all.stream()
                .filter(c -> c.id().equals(chosen))
                .findFirst()
                .or(() ->
                        all.stream().filter(PaymentMethods.SavedCard::isDefault).findFirst())
                .or(() -> all.stream().findFirst())
                .map(PaymentMethods.SavedCard::id)
                .orElse(null);
    }

    /** Books a paid period, opens the next one and says so. */
    private PlusSchedule paid(PlusSchedule s, PlusPeriod p, Charged.Paid paid, Instant now, boolean tell) {
        var period = p.withState(PlusPeriod.PAID)
                .withPaymentIntent(paid.paymentIntent())
                .withPaidAt(now)
                .withNextAttemptAt(null)
                .withFailure(null);
        ledger.post(LedgerEntry.plusInvoicePaid(period.id(), period.totalCents(), period.taxCents(), now));
        period = recordTax(period);
        store.update(period, now);
        var schedule = s.withStatus(PlusSchedule.ACTIVE).withCurrentPeriodEnd(period.endsAt());
        store.insert(
                PlusPeriod.scheduled(
                        Ids.next(),
                        s.id(),
                        period.seq() + 1,
                        period.endsAt(),
                        schedule.rule().after(period.endsAt()),
                        schedule.plan(),
                        schedule.priceCents()),
                now);
        store.update(schedule, now);
        notice(schedule, period, "paid", null, null, now);
        if (tell) {
            changed(schedule, now);
        }
        return schedule;
    }

    /** A refused renewal: tried again on the retry schedule, or the membership lapses. */
    private void refused(PlusSchedule s, PlusPeriod p, String code, Instant now) {
        var retry = retries.next(p.startsAt(), p.attempts()).orElse(null);
        var action = "authentication_required".equals(code);
        if (retry == null) {
            store.update(p.withState(PlusPeriod.LAPSED).withFailure(code).withNextAttemptAt(null), now);
            var ended = end(s, "lapsed", now, true);
            notice(ended, p, "lapsed", null, code, now);
            return;
        }
        var period = p.withState(action ? PlusPeriod.REQUIRES_ACTION : PlusPeriod.RETRYING)
                .withFailure(code)
                .withNextAttemptAt(retry.isBefore(now) ? now : retry);
        store.update(period, now);
        var schedule = s.withStatus(PlusSchedule.PAST_DUE);
        store.update(schedule, now);
        notice(
                schedule,
                period,
                action ? "action_required" : "declined",
                period.nextAttemptAt(),
                action ? null : code,
                now);
        changed(schedule, now);
    }

    private PlusSchedule end(PlusSchedule s, String reason, Instant now, boolean tell) {
        store.open(s.id())
                .ifPresent(p -> store.update(p.withState(PlusPeriod.VOID).withNextAttemptAt(null), now));
        var ended = s.end(reason, now);
        store.update(ended, now);
        if (tell) {
            changed(ended, now);
        }
        return ended;
    }

    private PlusPeriod recordTax(PlusPeriod p) {
        var calculation = p.taxCalculation();
        if (calculation == null || p.taxTransaction() != null) {
            return p;
        }
        try {
            var recorded = taxes.record(
                    calculation,
                    "plus_" + p.id(),
                    Map.of("northline_kind", "plus", "northline_plus_period", p.id()),
                    StripeIdempotencyKeys.of("plus-tax-record", p.id()));
            return p.withTaxTransaction(recorded.transaction());
        } catch (RuntimeException e) { // reported later by reportTax
            log.warn("Plus period {}: the tax wasn't recorded yet ({})", p.id(), e.getMessage());
            return p;
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int reportTax(int limit) {
        var n = 0;
        for (var id : store.untaxed(limit)) {
            var done = perPeriod.execute(_ -> store.lockPeriodIfFree(id)
                    .map(p -> {
                        var recorded = recordTax(p);
                        store.update(recorded, clock.instant());
                        return recorded.taxTransaction() != null;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean paymentIntentChanged(String paymentIntent) {
        var known = store.byPaymentIntent(paymentIntent).orElse(null);
        if (known == null) {
            return false;
        }
        var p = store.lockPeriod(known.id()).orElseThrow();
        if (!p.open()) {
            return true; // settled already
        }
        var s = store.lockSchedule(p.scheduleId()).orElseThrow();
        if (!s.ended() && gateway.chargeState(paymentIntent) instanceof Charged.Paid paid) {
            paid(s, p, paid, clock.instant(), true);
        }
        return true;
    }

    @Override
    public Refunded refund(String periodId, String staffId, String role) {
        var p = store.lockPeriod(periodId).orElseThrow(() -> new NotFound("plus_period", periodId));
        if (!PlusPeriod.PAID.equals(p.state()) || p.paymentIntent() == null) {
            throw new Conflict("plus_not_refundable", NOT_REFUNDABLE);
        }
        var s = store.lockSchedule(p.scheduleId()).orElseThrow();
        var now = clock.instant();
        var total = p.totalCents();
        var refundId = gateway.refund(
                Objects.requireNonNull(p.paymentIntent()),
                total,
                Map.of("northline_kind", "plus", "northline_plus_period", p.id()),
                StripeIdempotencyKeys.of("plus-refund", p.id()));
        ledger.post(LedgerEntry.plusRefunded(p.id(), total, p.taxCents(), now));
        var taxTransaction = p.taxTransaction();
        if (taxTransaction != null) {
            try {
                taxes.reverse(
                        taxTransaction,
                        "plus_refund_" + p.id(),
                        total,
                        Map.of("northline_kind", "plus", "northline_plus_period", p.id()),
                        StripeIdempotencyKeys.of("plus-tax-reverse", p.id()));
            } catch (RuntimeException e) {
                log.warn("Plus period {}: the tax reversal wasn't recorded ({}); reconcile it", p.id(), e.getMessage());
            }
        }
        var refunded = p.withState(PlusPeriod.REFUNDED).withRefundId(refundId).withRefundedAt(now);
        store.update(refunded, now);
        var current = !now.isBefore(p.startsAt()) && now.isBefore(p.endsAt());
        var schedule = current && !s.ended() ? end(s, "refunded", now, true) : s;
        notice(schedule, refunded, "refunded", null, null, now);
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                "payments.plus_refunded",
                "plus_period",
                p.id(),
                Map.of("state", p.state()),
                Map.of(
                        "state",
                        PlusPeriod.REFUNDED,
                        "totalCents",
                        String.valueOf(total),
                        "ended",
                        String.valueOf(current))));
        return new Refunded(period(refunded), refundId, total, current);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Period> periodsOf(String householdId) {
        return store.ofHousehold(householdId).stream()
                .flatMap(s -> store.periods(s.id(), 100).stream())
                .map(NorthlineMembershipBilling::period)
                .toList();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private PlusSchedule running(String subscriptionId) {
        var s = store.lockSchedule(subscriptionId).orElseThrow(() -> new NotFound("plus_membership", subscriptionId));
        if (s.ended()) {
            throw new Conflict("plus_none", "Your household doesn't have Northline Plus.");
        }
        return s;
    }

    private static CanadianTax.Province province(String code) {
        return CanadianTax.Province.of(code).orElseThrow(() -> RuleViolation.of("province", "allowed", NO_ADDRESS));
    }

    private Billed billed(PlusSchedule s, @Nullable String clientSecret) {
        return new Billed(
                s.id(),
                s.status(),
                s.plan(),
                s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(),
                clientSecret,
                clientSecret == null ? null : settings.publishableKey());
    }

    private static Period period(PlusPeriod p) {
        return new Period(
                p.id(), p.startsAt(), p.endsAt(), p.plan(), p.state(), p.amountCents(), p.taxCents(), p.paidAt());
    }

    private void changed(PlusSchedule s, Instant now) {
        events.publishEvent(new PlusBillingChanged(
                Ids.next(),
                now,
                s.id(),
                s.householdId(),
                s.plan(),
                s.status(),
                s.currentPeriodEnd(),
                s.cancelAtPeriodEnd()));
    }

    private void notice(
            PlusSchedule s,
            PlusPeriod p,
            String outcome,
            @Nullable Instant retryAt,
            @Nullable String code,
            Instant now) {
        events.publishEvent(new PlusPaymentUpdated(
                Ids.next(),
                now,
                p.id(),
                s.payerId(),
                outcome,
                p.plan(),
                p.amountCents(),
                p.taxCents(),
                p.startsAt(),
                p.endsAt(),
                retryAt,
                code));
    }
}
