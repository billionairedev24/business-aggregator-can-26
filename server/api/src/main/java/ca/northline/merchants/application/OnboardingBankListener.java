package ca.northline.merchants.application;

import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.payments.api.PayoutAccountChanged;
import ca.northline.payments.api.PayoutBankAccounts;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-24: when the owner confirms a payout bank account (Payouts › Change, Stripe Financial Connections or typed details),
 * onboarding's "Bank account for payouts" check that was waiting for it is verified with the account's label. Idempotent:
 * a verified check is left alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OnboardingBankListener {

    private final VerificationRepository verifications;
    private final PayoutBankAccounts accounts;
    private final Clock clock;

    @ApplicationModuleListener
    void on(PayoutAccountChanged event) {
        var label = accounts.current(event.merchantId()).orElse(null);
        if (label == null) {
            return;
        }
        verifications.listFor(event.merchantId()).stream()
                .filter(v -> v.kind() == CheckKind.BANK && v.getStatus() != VerificationStatus.VERIFIED)
                .forEach(v -> {
                    v.verify(label, clock.instant());
                    verifications.save(v);
                    log.info("Onboarding bank check of {} verified: {}", event.merchantId(), label);
                });
    }
}
