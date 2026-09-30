package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/**
 * Reconciliation of the Stripe Tax sync for one quarter: reports whatever is still pending (whatever its attempts),
 * compares each recorded transaction with what Stripe Tax holds, and rebuilds the quarter's rows of
 * {@code payments.tax_jurisdiction_totals}. Idempotent; run nightly by the payments job and on demand by staff.
 */
public interface ReconcileTax {

    /**
     * @param reported pending / failed transactions reported now
     * @param checked recorded transactions compared with Stripe Tax
     * @param mismatched of those, how many Stripe Tax holds a different tax for (logged as warnings)
     * @param rows read-model rows rebuilt
     * @param stillPending transactions that still couldn't be reported
     */
    record Report(String period, int reported, int checked, int mismatched, int rows, int stillPending) {}

    /** @param period {@code 2026-Q3}; null = the current quarter (Edmonton) */
    Report reconcile(@Nullable String period);
}
