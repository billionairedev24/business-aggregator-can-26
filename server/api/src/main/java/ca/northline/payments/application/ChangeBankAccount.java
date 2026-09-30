package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutAccount;
import org.jspecify.annotations.Nullable;

/**
 * "Change payout account": connect instantly (Stripe Financial Connections) or type the details → confirm with a fresh
 * second factor → 24 h hold → takes over. Every step is in the merchant's audit log.
 */
public interface ChangeBankAccount {

    BankLinking.LinkSession linkSession(String merchantId);

    /**
     * @param linkedAccountRef the bank-account token Stripe.js' {@code collectBankAccountToken} returned ({@code instant})
     * @param financialConnectionsAccount the Financial Connections account behind it ({@code fca_…}, {@code instant})
     * @param institution / transit / accountNumber / holderName typed details ({@code manual})
     * @param role the caller's team role, for the audit log
     */
    record Prepare(
            String merchantId,
            PayoutAccount.Method method,
            @Nullable String linkedAccountRef,
            @Nullable String financialConnectionsAccount,
            @Nullable String institution,
            @Nullable String transit,
            @Nullable String accountNumber,
            @Nullable String holderName,
            String userId,
            String role) {}

    /** Attaches the account at Stripe and keeps it as a draft (shown for confirmation). */
    PayoutAccount prepare(Prepare command);

    /** Money-moving (step-up checked by the caller): the new account takes over after the 24 h hold. */
    PayoutAccount confirm(String merchantId, String accountId, String userId, String role);
}
