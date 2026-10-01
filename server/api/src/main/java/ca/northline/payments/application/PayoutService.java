package ca.northline.payments.application;

import ca.northline.payments.api.PayoutPlan;
import ca.northline.payments.api.RecentPayouts;
import ca.northline.payments.domain.Fees;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutMessages;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payouts: overview, history, schedule, instant payouts, and the scheduled payout run. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PayoutService implements ViewPayouts, MovePayouts, PayoutPlan, RecentPayouts {

    static final String PAYOUTS_DISABLED =
            "Stripe has paused payouts on this account. Finish the steps in Settings › Stripe & compliance.";

    private final PayoutRepository payouts;
    private final LedgerRepository ledger;
    private final MerchantBalances balances;
    private final PayoutGateway gateway;
    private final PaymentGateway charges;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final BusinessTime time;

    @Override
    public Overview overview(String merchantId) {
        var now = clock.instant();
        var schedule = schedule(merchantId);
        var pending = payouts.pendingAccount(merchantId).orElse(null);
        var pausedUntil = pending == null ? null : pending.getEffectiveAt();
        var available = balances.of(merchantId).availableCents();
        var reserve = schedule.reserve().heldBack(available);
        var instantEligible = payouts.connectedAccount(merchantId)
                .map(PayoutRepository.ConnectedAccount::instantPayouts)
                .orElse(false);
        return new Overview(
                available,
                available - reserve,
                reserve,
                schedule.nextAfter(now, pausedUntil, time.of(merchantId)).orElse(null),
                schedule,
                payouts.activeAccount(merchantId).orElse(null),
                pending,
                pausedUntil != null && pausedUntil.isAfter(now) ? pausedUntil : null,
                instantEligible);
    }

    @Override
    public List<Payout> history(String merchantId, int limit) {
        return payouts.history(merchantId, Math.clamp(limit, 1, 500));
    }

    @Override
    public List<PayoutRef> recent(String merchantId, int limit) {
        return history(merchantId, limit).stream()
                .map(p -> new PayoutRef(
                        p.getId(),
                        p.getAmountCents(),
                        p.getKind().code(),
                        p.getState().code(),
                        p.getArrivesAt()))
                .toList();
    }

    @Override
    public Preview preview(String merchantId, PayoutSchedule schedule) {
        var pausedUntil = payouts.pendingAccount(merchantId)
                .map(PayoutAccount::getEffectiveAt)
                .orElse(null);
        var available = balances.of(merchantId).availableCents();
        return new Preview(
                schedule.nextAfter(clock.instant(), pausedUntil, time.of(merchantId))
                        .orElse(null),
                available - schedule.reserve().heldBack(available));
    }

    @Override
    @Transactional
    public Payout instant(InstantCommand command) {
        var merchantId = command.merchantId();
        var now = clock.instant();
        var connected = payouts.connectedAccount(merchantId)
                .filter(PayoutRepository.ConnectedAccount::instantPayouts)
                .orElseThrow(() -> new Conflict(
                        "instant_unavailable", "Instant payouts need an eligible Canadian debit-linked account."));
        if (!connected.payoutsEnabled()) {
            throw new Conflict("payouts_disabled", PAYOUTS_DISABLED);
        }
        requireNotPaused(merchantId, now);
        var account = payouts.activeAccount(merchantId)
                .orElseThrow(() -> new Conflict("no_payout_account", "Add a bank account first."));
        var overview = overview(merchantId);
        if (command.amountCents() < Fees.INSTANT_MIN_AMOUNT_CENTS) {
            throw RuleViolation.of("amountCents", "range", PayoutMessages.AMOUNT_MIN);
        }
        if (command.amountCents() > overview.payableCents()) {
            throw RuleViolation.of(
                    "amountCents", "range", PayoutMessages.AMOUNT_MAX.formatted(money(overview.payableCents())));
        }
        var fee = Fees.instantFee(command.amountCents());
        var sent = gateway.payout(
                connected.stripeAccount(),
                command.amountCents() - fee,
                true,
                account.getExternalRef(),
                StripeIdempotencyKeys.fromClient(
                        "instant-payout", merchantId + ":" + command.userId(), command.idempotencyKey()));
        var itemCount = payouts.releasedSince(
                merchantId, payouts.lastPayoutAt(merchantId).orElse(null));
        var payout = Payout.sent(
                Payout.Kind.INSTANT,
                merchantId,
                command.amountCents(),
                fee,
                sent.payoutId(),
                sent.arrivesAt(),
                itemCount,
                account,
                command.userId(),
                now);
        payout.feeRecovered(gateway.recoverFee(
                connected.stripeAccount(),
                fee,
                sent.payoutId(),
                StripeIdempotencyKeys.of("instant-payout-fee", sent.payoutId())));
        record(payout);
        return payout;
    }

    @Override
    @Transactional
    public Overview changeSchedule(String merchantId, PayoutSchedule schedule, String userId) {
        // Stripe's own schedule stays `manual`: the scheduled run below creates every payout
        payouts.saveSchedule(merchantId, schedule, userId, clock.instant());
        return overview(merchantId);
    }

    @Override
    public Optional<Plan> of(String merchantId) {
        return payouts.connectedAccount(merchantId).map(connected -> {
            var schedule = schedule(merchantId);
            var weekday = schedule.frequency() == PayoutSchedule.Frequency.WEEKLY && schedule.weekday() != null
                    ? java.time.DayOfWeek.of(schedule.weekday()).name().toLowerCase(Locale.ROOT)
                    : null;
            return new Plan(schedule.frequency().code(), weekday, connected.instantPayouts());
        });
    }

    /** Scheduled payouts whose time came today (idempotent per merchant and day). */
    @Transactional
    int runScheduled() {
        var now = clock.instant();
        int sent = 0;
        for (var merchantId : payouts.merchantsWithSchedules()) {
            // each business's own day and 9:00 (region model): a run sweeps every zone it has reached
            var zone = time.of(merchantId);
            var today = LocalDate.ofInstant(now, zone);
            var payoutTime =
                    today.atTime(PayoutSchedule.PAYOUT_TIME).atZone(zone).toInstant();
            if (now.isBefore(payoutTime)) {
                continue;
            }
            var dayStart = today.atStartOfDay(zone).toInstant();
            var dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
            var schedule = schedule(merchantId);
            if (!schedule.isPayoutDay(today) || payouts.scheduledBetween(merchantId, dayStart, dayEnd)) {
                continue;
            }
            if (payouts.pendingAccount(merchantId).isPresent()) {
                continue; // 24 h hold after a bank change
            }
            var connected = payouts.connectedAccount(merchantId).orElse(null);
            var account = payouts.activeAccount(merchantId).orElse(null);
            var amount = overview(merchantId).payableCents();
            if (connected == null || !connected.payoutsEnabled() || account == null || amount <= 0) {
                continue;
            }
            var result = gateway.payout(
                    connected.stripeAccount(),
                    amount,
                    false,
                    account.getExternalRef(),
                    StripeIdempotencyKeys.of("scheduled-payout", merchantId, today.toString()));
            var itemCount = payouts.releasedSince(
                    merchantId, payouts.lastPayoutAt(merchantId).orElse(null));
            record(Payout.sent(
                    Payout.Kind.SCHEDULED,
                    merchantId,
                    amount,
                    0,
                    result.payoutId(),
                    result.arrivesAt(),
                    itemCount,
                    account,
                    null,
                    now));
            sent++;
        }
        return sent;
    }

    /**
     * The fallback reconciler: in-transit payouts whose arrival time passed by {@link #RECONCILE_AFTER} (the fake:
     * at once) are looked up at Stripe and settled like a webhook would. Stripe's {@code payout.*} webhooks normally
     * get there first; this catches the ones whose event never came.
     */
    @Transactional
    int settle() {
        var now = clock.instant();
        int n = 0;
        for (var payout : payouts.inTransit(500)) {
            var stripePayout = payout.getStripePayout();
            if (payout.getArrivesAt().plus(reconcileAfter()).isAfter(now) || stripePayout == null) {
                continue;
            }
            var connected = payouts.connectedAccount(payout.getMerchantId()).orElse(null);
            var state = connected == null
                    ? Payout.State.PAID
                    : gateway.payoutState(connected.stripeAccount(), stripePayout);
            if (apply(payout, state, null, now)) {
                n++;
            }
        }
        return n;
    }

    /** Payouts reported by Stripe webhooks this long after their arrival date are left to the webhook. */
    static final java.time.Duration RECONCILE_AFTER = java.time.Duration.ofHours(24);

    private java.time.Duration reconcileAfter() {
        return gateway.webhooksDeliver() ? RECONCILE_AFTER : java.time.Duration.ZERO;
    }

    /** A {@code payout.paid} / {@code payout.failed} / {@code payout.canceled} webhook; false for unknown payouts. */
    @Transactional
    boolean stripePayout(StripeEvent event, Payout.State outcome) {
        var id = event.object().id();
        var payout = id == null ? null : payouts.byStripePayout(id).orElse(null);
        if (payout == null) {
            log.warn("Stripe payout {} isn't one of Northline's", id);
            return false;
        }
        apply(payout, outcome, event.object().text("failure_code"), event.created());
        return true;
    }

    /** Paid → paid; failed / canceled → returned to the merchant's balance (once), instant fee given back. */
    private boolean apply(Payout payout, Payout.State state, @Nullable String failureCode, Instant at) {
        return switch (state) {
            case PAID -> {
                if (payout.paid()) {
                    payouts.update(payout);
                    yield true;
                }
                yield false;
            }
            case FAILED, CANCELED ->
                payout.returned(state, failureCode, at)
                        .map(failed -> {
                            giveInstantFeeBack(payout);
                            payouts.update(payout);
                            ledger.post(LedgerEntry.payoutReturned(payout, at));
                            events.publishEvent(failed);
                            log.warn(
                                    "Payout {} of merchant {} was {} by Stripe ({})",
                                    payout.getId(),
                                    payout.getMerchantId(),
                                    state.code(),
                                    failureCode);
                            return true;
                        })
                        .orElse(false);
            case PENDING, IN_TRANSIT -> false;
        };
    }

    /** The fee recovered for a returned instant payout goes back to the connected account. */
    private void giveInstantFeeBack(Payout payout) {
        if (payout.getStripeFeeTransfer() == null || payout.getFeeCents() == 0) {
            return;
        }
        payouts.connectedAccount(payout.getMerchantId())
                .ifPresent(connected -> payout.feeReturned(charges.transfer(new PaymentGateway.Transfer(
                        connected.stripeAccount(),
                        payout.getFeeCents(),
                        "payout:" + payout.getId(),
                        null,
                        java.util.Map.of(
                                "northline_kind", "instant_payout_fee_returned",
                                "northline_payout_id", payout.getId(),
                                "northline_merchant_id", payout.getMerchantId()),
                        StripeIdempotencyKeys.of("instant-payout-fee-return", payout.getId())))));
    }

    private void record(Payout payout) {
        payouts.insert(payout);
        ledger.post(LedgerEntry.paidOut(payout));
        events.publishEvent(payout.sentEvent());
    }

    private void requireNotPaused(String merchantId, Instant now) {
        var pending = payouts.pendingAccount(merchantId).orElse(null);
        @Nullable Instant until = pending == null ? null : pending.getEffectiveAt();
        if (until != null && until.isAfter(now)) {
            throw new Conflict(
                    "payouts_paused",
                    "Payouts are paused until %s after the bank account change."
                            .formatted(DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.CANADA)
                                    .withZone(time.of(merchantId))
                                    .format(until)));
        }
    }

    private PayoutSchedule schedule(String merchantId) {
        return payouts.schedule(merchantId).orElse(PayoutSchedule.DEFAULT);
    }

    private static String money(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.CANADA).format(cents / 100.0);
    }
}
