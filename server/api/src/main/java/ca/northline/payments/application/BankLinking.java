package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: linking the bank account payouts go to, on the merchant's Stripe connected account. Instantly through
 * Stripe Financial Connections — the api opens a session for the connected account, the Studio runs Stripe.js'
 * {@code collectBankAccountToken} with its client secret, and the token becomes the connected account's external
 * account — or from typed institution / transit / account numbers, tokenized at Stripe. Account numbers never reach
 * Northline's database: only the institution's name, the last 4 digits and Stripe's references. Implemented by
 * stripe-java when a Stripe key is configured, by a local fake (a simulated bank picker, no Stripe.js) otherwise.
 */
public interface BankLinking {

    /**
     * How the Studio links a bank: {@code stripe} (Stripe.js Financial Connections with {@code clientSecret} and
     * {@code publishableKey}) or {@code fake} (the Studio's simulated bank picker).
     */
    record LinkSession(
            String mode,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}

    /**
     * A bank account now attached to the connected account ({@code externalRef} = {@code ba_…}).
     *
     * @param institutionNumber / transitNumber kept for typed details only; null for a Financial Connections link
     * @param financialConnectionsAccount {@code fca_…} for an instant link
     */
    record Linked(
            String externalRef,
            String institutionName,
            String last4,
            @Nullable String institutionNumber,
            @Nullable String transitNumber,
            @Nullable String financialConnectionsAccount) {}

    /** A Financial Connections session for the connected account (a new one each time). */
    LinkSession start(String connectedAccount);

    /**
     * The account the owner picked: {@code bankToken} ({@code btok_…}) from {@code collectBankAccountToken}, and the
     * Financial Connections account behind it. Refused ({@link NotLinkable}) when the Financial Connections account
     * isn't this connected account's or is no longer active.
     */
    Linked link(String connectedAccount, String bankToken, @Nullable String financialConnectionsAccount);

    /** Typed institution / transit / account: tokenized at Stripe and attached. */
    Linked manual(String connectedAccount, String institution, String transit, String accountNumber, String holderName);

    /** The linked account can't be used (someone else's, disconnected, unknown token). */
    final class NotLinkable extends RuntimeException {
        public NotLinkable(String message) {
            super(message);
        }
    }
}
