package ca.northline.hire.application;

import ca.northline.identity.api.SecondFactors;
import ca.northline.payments.api.PaymentStepUp;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The S-51 payment step-up rule for the Services journey: a sign-in with a second factor ({@code acr=mfa}) pays
 * directly; a phone-code sign-in sends a fresh {@code X-Step-Up} proof from its passkey or authenticator; an account
 * with neither enrols a passkey first. Checked only when money is about to be held (a free consultation isn't).
 */
@Component
@RequiredArgsConstructor
class PaymentGate {

    static final String STEP_UP = "Confirm it's you with your passkey or authenticator app to pay.";
    static final String ENROL = "Add a passkey to pay: payments sit behind a second factor.";

    private final PaymentStepUp stepUp;
    private final SecondFactors factors;

    void require(String userId, boolean mfa, @Nullable String proof) {
        if (mfa || stepUp.verified(userId, proof)) {
            return;
        }
        if (factors.hasSecondFactor(userId)) {
            throw new SecondFactorRequired("step_up_required", STEP_UP);
        }
        throw new SecondFactorRequired("second_factor_required", ENROL);
    }
}
