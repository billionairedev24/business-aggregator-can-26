package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutAccount;
import org.jspecify.annotations.Nullable;

/** "Change payout account": connect instantly or type the details → confirm with passkey → 24 h hold → takes over. */
public interface ChangeBankAccount {

    PayoutGateway.LinkSession linkSession(String merchantId);

    /**
     * @param linkedAccountRef Financial Connections account ({@code instant})
     * @param institution / transit / accountNumber / holderName typed details ({@code manual})
     */
    record Prepare(
            String merchantId,
            PayoutAccount.Method method,
            @Nullable String linkedAccountRef,
            @Nullable String institution,
            @Nullable String transit,
            @Nullable String accountNumber,
            @Nullable String holderName,
            String userId) {}

    /** Verifies the account at Stripe and keeps it as a draft (shown for confirmation). */
    PayoutAccount prepare(Prepare command);

    /** Money-moving: the new account takes over after the 24 h hold; payouts pause meanwhile. */
    PayoutAccount confirm(String merchantId, String accountId, String userId);
}
