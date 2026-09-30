package ca.northline.payments.api;

/**
 * The merchants module created or found the business's Stripe Connect Express account ({@code acct_…}): payments
 * records it (transfers and payouts go there) and turns Stripe's automatic payouts off for it, because Northline's
 * payout run decides when money leaves (reserve, open cases, bank-change hold). Idempotent.
 */
public interface ConnectedAccounts {

    void linked(String merchantId, String stripeAccount);
}
