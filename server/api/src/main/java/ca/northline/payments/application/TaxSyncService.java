package ca.northline.payments.application;

import ca.northline.payments.application.TaxRepository.Calculation;
import ca.northline.payments.application.TaxRepository.State;
import ca.northline.payments.application.TaxRepository.TotalsKey;
import ca.northline.payments.application.TaxRepository.Transaction;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reports tax transactions to Stripe Tax and keeps {@code payments.tax_jurisdiction_totals} (the Studio's tax table)
 * in step. Each transaction is reported in its own database transaction under a row lock, so the listener, the retry
 * job and the reconciliation never report one twice; Stripe calls carry idempotency keys derived from the reference,
 * so a retry after a crash repeats the same call. A sale is recorded from its checkout calculation (recalculated for
 * the same province and amount when it expired or there was none); a reversal needs its sale recorded first.
 */
@Slf4j
@Service
class TaxSyncService implements ReconcileTax {

    static final int MAX_ATTEMPTS = 10;

    /** A calculation this close to Stripe's 90-day expiry is recalculated rather than risked. */
    static final Duration EXPIRY_MARGIN = Duration.ofHours(1);

    private static final Pattern PERIOD = Pattern.compile("\\d{4}-Q[1-4]");
    private static final int BATCH = 200;

    private final TaxRepository taxes;
    private final TaxGateway gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TaxSyncService(
            TaxRepository taxes, TaxGateway gateway, PlatformTransactionManager transactionManager, Clock clock) {
        this.taxes = taxes;
        this.gateway = gateway;
        this.clock = clock;
        // each transaction in its own database transaction, also when called from the listener's
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Reports one transaction; a failure is recorded on it (and retried by the job) instead of propagating. */
    boolean syncOrRecordFailure(String reference) {
        try {
            return Boolean.TRUE.equals(transactions.execute(_ -> sync(reference)));
        } catch (RuntimeException e) {
            log.warn("Tax transaction {} not reported to Stripe Tax: {}", reference, e.toString());
            transactions.executeWithoutResult(_ -> taxes.failed(reference, String.valueOf(e)));
            return false;
        }
    }

    /** The retry job: pending and failed transactions, oldest first. Returns how many were reported. */
    int syncPending() {
        return syncAll(MAX_ATTEMPTS);
    }

    private int syncAll(int maxAttempts) {
        int n = 0;
        for (var reference : Objects.requireNonNull(transactions.execute(_ -> taxes.pending(maxAttempts, BATCH)))) {
            if (syncOrRecordFailure(reference)) {
                n++;
            }
        }
        return n;
    }

    /** Nightly: the current quarter, and the previous one while its late refunds and retries can still land. */
    int reconcileRecent() {
        var now = clock.instant();
        var current = CanadianTax.period(now);
        var previous = CanadianTax.period(now.minus(Duration.ofDays(92)));
        var a = reconcile(current);
        var b = previous.equals(current) ? null : reconcile(previous);
        return a.reported() + a.rows() + (b == null ? 0 : b.reported() + b.rows());
    }

    @Override
    public Report reconcile(@Nullable String period) {
        var now = clock.instant();
        var quarter = period == null ? CanadianTax.period(now) : period.strip();
        if (!PERIOD.matcher(quarter).matches()) {
            throw RuleViolation.of("period", "format", "Use a quarter like 2026-Q3.");
        }
        var reported = syncAll(Integer.MAX_VALUE);
        int checked = 0;
        int mismatched = 0;
        for (var t : Objects.requireNonNull(transactions.execute(_ -> taxes.unreconciled(quarter, 1000)))) {
            var stripeTransaction = Objects.requireNonNull(t.stripeTransaction());
            long stripeTax;
            try {
                stripeTax = gateway.taxOf(stripeTransaction);
            } catch (RuntimeException e) {
                log.warn(
                        "Tax reconciliation: couldn't read {} ({}): {}",
                        stripeTransaction,
                        t.reference(),
                        e.toString());
                continue;
            }
            transactions.executeWithoutResult(_ -> taxes.reconciled(t.reference(), stripeTax, now));
            checked++;
            if (stripeTax != t.taxCents()) {
                mismatched++;
                log.warn(
                        "Tax reconciliation: {} ({}) — Northline {} ¢, Stripe Tax {} ¢",
                        t.reference(),
                        stripeTransaction,
                        t.taxCents(),
                        stripeTax);
            }
        }
        var keys = Objects.requireNonNull(transactions.execute(_ -> taxes.totalsOf(quarter)));
        keys.forEach(key -> transactions.executeWithoutResult(_ -> taxes.refreshTotals(key, now)));
        var stillPending = Objects.requireNonNull(transactions.execute(_ -> taxes.pending(Integer.MAX_VALUE, 10_000)))
                .size();
        var report = new Report(quarter, reported, checked, mismatched, keys.size(), stillPending);
        log.info("Tax reconciliation {}", report);
        return report;
    }

    /** What reporting one transaction did at Stripe Tax (and which calculation a sale was recorded from). */
    private record Reported(
            TaxGateway.Recorded recorded, @Nullable String calculationId) {}

    private boolean sync(String reference) {
        var t = taxes.lock(reference).orElse(null);
        if (t == null || t.state() == State.RECORDED) {
            return false;
        }
        var now = clock.instant();
        var reported = switch (t.kind()) {
            case SALE -> recordSale(t, now);
            case REVERSAL -> recordReversal(t);
        };
        var recorded = reported.recorded();
        taxes.recorded(reference, recorded.transaction(), recorded.taxCents(), reported.calculationId(), now);
        var stripeTax = recorded.taxCents();
        if (stripeTax != null && stripeTax != t.taxCents()) {
            log.warn(
                    "Tax transaction {}: Northline collected {} ¢, Stripe Tax recorded {} ¢ ({})",
                    reference,
                    t.taxCents(),
                    stripeTax,
                    recorded.transaction());
        }
        taxes.refreshTotals(new TotalsKey(t.merchantId(), t.period(), t.jurisdiction()), now);
        return true;
    }

    private Reported recordSale(Transaction t, Instant now) {
        var calculation = t.calculationId() == null
                ? null
                : taxes.calculation(t.calculationId())
                        .filter(c -> c.expiresAt().isAfter(now.plus(EXPIRY_MARGIN)))
                        .filter(c -> c.amountCents() == t.amountCents())
                        .orElse(null);
        if (calculation == null) {
            // no checkout calculation, or Stripe no longer has it: the same province and amount, priced now
            var fresh = gateway.calculate(new TaxGateway.Calculate(
                    t.reference(),
                    t.escrowKind(),
                    t.province(),
                    null,
                    t.amountCents(),
                    StripeIdempotencyKeys.of("tax-calculation", t.reference())));
            calculation = new Calculation(
                    Ids.next(),
                    fresh.calculation(),
                    t.merchantId(),
                    t.escrowKind(),
                    t.province(),
                    t.amountCents(),
                    fresh.taxCents(),
                    fresh.lines(),
                    null,
                    null,
                    fresh.expiresAt(),
                    now);
            taxes.insertCalculation(calculation);
        }
        var recorded = gateway.record(
                calculation.stripeCalculation(),
                t.reference(),
                metadata(t),
                StripeIdempotencyKeys.of("tax-transaction", t.reference()));
        return new Reported(recorded, calculation.id());
    }

    private Reported recordReversal(Transaction t) {
        var originalReference = Objects.requireNonNull(t.originalReference());
        var original = taxes.find(originalReference)
                .orElseThrow(() -> new IllegalStateException(
                        "sale " + originalReference + " of reversal " + t.reference() + " is missing"));
        if (original.state() != State.RECORDED) {
            sync(originalReference); // report the sale first, in this transaction
            original = taxes.find(originalReference).orElseThrow();
        }
        var saleTransaction = original.stripeTransaction();
        if (original.state() != State.RECORDED || saleTransaction == null) {
            throw new IllegalStateException("sale " + originalReference + " isn't reported to Stripe Tax yet");
        }
        var recorded = gateway.reverse(
                saleTransaction,
                t.reference(),
                t.amountCents() + t.taxCents(),
                metadata(t),
                StripeIdempotencyKeys.of("tax-reversal", t.reference()));
        return new Reported(recorded, null);
    }

    /** Northline ids only. */
    private static Map<String, String> metadata(Transaction t) {
        return Map.of(
                "northline_merchant_id", t.merchantId(),
                "northline_escrow_id", t.escrowId(),
                "northline_reference", t.reference());
    }
}
