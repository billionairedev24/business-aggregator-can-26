package ca.northline.payments.application;

import ca.northline.payments.api.CourierPay;
import ca.northline.payments.application.CourierPayoutStore.CourierAccount;
import ca.northline.payments.application.CourierPayoutStore.CourierPayout;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CourierPay} (S-151). A run's pay is posted once ({@code courier_pay} → {@code courier:<user id>}); tips
 * already land on the same account. The weekly run pays each courier whose payout account Stripe enabled: a transfer
 * from the platform to their Express account (transfer group {@code courier:<user id>}), then a standard payout to the
 * bank Stripe collected — the same two steps as a merchant's payout, through the same gateways. A payout that fails
 * gives the amount back to the courier's balance for the next run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CourierPayoutService implements CourierPay {

    /** Below this a payout waits for the next run (Stripe's fees aren't worth a few cents). */
    static final long MINIMUM_CENTS = 100;

    private static final int RECENT = 20;

    private final LedgerRepository ledger;
    private final CourierTipService tips;
    private final CourierPayoutStore store;
    private final CourierAccountGateway accounts;
    private final PaymentGateway payments;
    private final PayoutGateway payouts;
    private final Clock clock;

    @Override
    public void earned(String runId, String courierUserId, long cents, Instant at) {
        if (cents <= 0 || ledger.posted(LedgerEntry.COURIER_RUN, runId)) {
            return;
        }
        ledger.post(LedgerEntry.courierPayEarned(runId, courierUserId, cents, at));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TipEarned> tips(String courierUserId, Instant from, Instant to) {
        return tips.allocatedTo(courierUserId, from, to).stream()
                .map(t -> new TipEarned(t.id(), t.orderId(), t.amountCents(), t.at()))
                .toList();
    }

    @Override
    public Status status(String courierUserId) {
        // an account Stripe hasn't enabled yet is read again (the webhook may be late; the fake sends none)
        store.account(courierUserId)
                .filter(a -> !a.payoutsEnabled())
                .ifPresent(a ->
                        store.updateAccount(a.stripeAccount(), accounts.account(a.stripeAccount()), clock.instant()));
        var account = store.account(courierUserId)
                .map(a -> new Account(a.payoutsEnabled(), a.requirementsDue(), a.bankLast4()))
                .orElse(null);
        var recent = store.payouts(courierUserId, RECENT).stream()
                .map(p -> new Payout(p.id(), p.amountCents(), p.state(), p.createdAt(), p.arrivesAt()))
                .toList();
        return new Status(ledger.balance(LedgerEntry.courier(courierUserId)), account, recent);
    }

    @Override
    public String onboardingLink(String courierUserId, String returnUrl, String refreshUrl) {
        var account = store.account(courierUserId).orElseGet(() -> {
            var created = new CourierAccount(
                    courierUserId, accounts.createAccount(courierUserId), false, 0, null, null, clock.instant());
            store.insert(created);
            return store.account(courierUserId).orElse(created);
        });
        return accounts.onboardingLink(account.stripeAccount(), returnUrl, refreshUrl);
    }

    // ── jobs ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The weekly run: every courier with an enabled account and at least $1.00 owed is paid. Returns payouts made. */
    int payAll() {
        int n = 0;
        for (var account : store.payable()) {
            try {
                if (payOne(account)) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.error("Courier {}: payout failed; retried on the next run", account.userId(), e);
            }
        }
        return n;
    }

    private boolean payOne(CourierAccount account) {
        var owed = ledger.balance(LedgerEntry.courier(account.userId()));
        if (owed < MINIMUM_CENTS) {
            return false;
        }
        var bank = account.externalRef();
        if (bank == null) {
            var fresh = accounts.account(account.stripeAccount());
            store.updateAccount(account.stripeAccount(), fresh, clock.instant());
            bank = fresh.externalRef();
            if (bank == null || !fresh.payoutsEnabled()) {
                log.warn("Courier {}: no payout bank at Stripe yet", account.userId());
                return false;
            }
        }
        var now = clock.instant();
        var payout = new CourierPayout(
                Ids.next(), account.userId(), account.stripeAccount(), owed, null, null, "pending", null, null, now);
        store.insertPayout(payout);
        var transfer = payments.transfer(new PaymentGateway.Transfer(
                account.stripeAccount(),
                owed,
                "courier:" + account.userId(),
                null,
                Map.of("northline_courier_user_id", account.userId(), "northline_courier_payout_id", payout.id()),
                StripeIdempotencyKeys.of("courier-transfer", payout.id())));
        var sent = payouts.payout(
                account.stripeAccount(), owed, false, bank, StripeIdempotencyKeys.of("courier-payout", payout.id()));
        ledger.post(LedgerEntry.courierPaidOut(payout.id(), account.userId(), owed, now));
        store.updatePayout(payout.with("in_transit", transfer, sent.payoutId(), sent.arrivesAt(), null));
        log.info("Courier {}: {} cents paid out ({})", account.userId(), owed, sent.payoutId());
        return true;
    }

    /** Payouts whose webhook never came (the fake sends none): their state is read from Stripe after arrival. */
    int settle() {
        var now = clock.instant();
        int n = 0;
        for (var p : store.arrivedBy(
                now.minus(payouts.webhooksDeliver() ? PayoutService.RECONCILE_AFTER : java.time.Duration.ZERO), 100)) {
            var stripePayout = p.stripePayout();
            if (stripePayout == null) {
                continue;
            }
            if (apply(p, payouts.payoutState(p.stripeAccount(), stripePayout).code(), null, now)) {
                n++;
            }
        }
        return n;
    }

    /** {@code payout.paid} / {@code failed} / {@code canceled} for a courier payout; false when it isn't one. */
    boolean stripePayout(StripeEvent event, String outcome) {
        var id = event.object().id();
        var payout = id == null ? null : store.byStripePayout(id).orElse(null);
        if (payout == null) {
            return false;
        }
        apply(payout, outcome, event.object().text("failure_code"), event.created());
        return true;
    }

    private boolean apply(CourierPayout p, String outcome, @Nullable String failure, Instant at) {
        if (!p.state().equals("in_transit") && !p.state().equals("pending")) {
            return false; // final already
        }
        switch (outcome) {
            case "paid" ->
                store.updatePayout(p.with("paid", p.stripeTransfer(), p.stripePayout(), p.arrivesAt(), null));
            case "failed", "canceled" -> {
                ledger.post(LedgerEntry.courierPayoutReturned(p.id(), p.userId(), p.amountCents(), at));
                store.updatePayout(p.with(outcome, p.stripeTransfer(), p.stripePayout(), p.arrivesAt(), failure));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** {@code account.updated} for a courier's account; false when the account isn't a courier's. */
    boolean accountUpdated(StripeEvent event) {
        var o = event.object();
        var account = o.id();
        if (account == null || store.byStripeAccount(account).isEmpty()) {
            return false;
        }
        var banks = o.objects("external_accounts", "data");
        var bank = banks.stream()
                .filter(b -> b.flag("default_for_currency"))
                .findFirst()
                .orElse(banks.isEmpty() ? null : banks.getFirst());
        store.updateAccount(
                account,
                new CourierAccountGateway.Account(
                        o.flag("payouts_enabled"),
                        o.strings("requirements", "currently_due").size(),
                        bank == null ? null : bank.id(),
                        bank == null ? null : bank.text("last4")),
                event.created());
        return true;
    }
}
