package ca.northline.payments.api;

import java.util.Optional;

/**
 * The bank account a merchant's payouts go to, for onboarding's "Bank account for payouts" check (S-24): linked in
 * Payouts through Stripe Financial Connections or typed details.
 */
public interface PayoutBankAccounts {

    /** "RBC ··8820" — the active account, else the one inside its 24 h hold; empty when none was linked. */
    Optional<String> current(String merchantId);
}
