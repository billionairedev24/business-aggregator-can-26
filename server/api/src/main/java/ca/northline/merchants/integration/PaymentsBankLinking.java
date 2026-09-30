package ca.northline.merchants.integration;

import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import ca.northline.payments.api.PayoutBankAccounts;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Onboarding's "Bank account for payouts" outside {@code local}/{@code test} (S-24): the bank is linked in Payouts
 * (Stripe Financial Connections, or typed details), so the check is verified with its label ("RBC ··8820") once one
 * exists; until then it waits ({@code submitted}) and {@code OnboardingBankListener} verifies it when the owner links
 * one.
 */
@Component
@Profile("!local & !test")
@RequiredArgsConstructor
class PaymentsBankLinking implements BankLinking {

    static final String AWAITING = "awaiting_bank_link";

    private final PayoutBankAccounts accounts;

    @Override
    public Outcome link(String merchantId) {
        return accounts.current(merchantId)
                .map(label -> new Outcome(true, label))
                .orElseGet(() -> new Outcome(false, AWAITING));
    }
}
