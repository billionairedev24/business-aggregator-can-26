package ca.northline.payments.application;

/**
 * Background work, run by the scheduler in {@code payments.infra} (off under the {@code test} profile; tests call it
 * directly). Each step is idempotent and returns how many records it changed.
 */
public interface PaymentsJobs {

    /** Escrow whose release time passed → merchant balance ({@code escrow.released}). */
    int releaseDueEscrows();

    /** Card holds about to lapse at Stripe are renewed (see {@code AuthorizationWindow}). */
    int renewAuthorizations();

    /** Stripe webhook events not processed yet (or failed), and the purge of old ones. */
    int processStripeEvents();

    /** Refund cases past their contest deadline, and goodwill offers past 72 h. */
    int lapseCases();

    /** The refund queue: approved refunds are paid ({@code refund.issued}). */
    int payRefundQueue();

    /** Tax transactions not reported to Stripe Tax yet (or failed), S-21. */
    int syncTax();

    /** Nightly: the Stripe Tax reconciliation of the current and previous quarter ({@link ReconcileTax}). */
    int reconcileTax();

    /** Bank accounts whose 24 h hold ended take over. */
    int activatePayoutAccounts();

    /** Scheduled payouts due today, and payouts that arrived. */
    int runPayouts();
}
