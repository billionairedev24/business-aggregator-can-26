package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: "Payouts always require a fresh authentication" (design 02). Checks the step-up proof the Studio got
 * from northline-auth ({@code POST /api/auth/step-up/*} after a passkey or authenticator code) and sends as the
 * {@code X-Step-Up} header: signed by the auth server, for this user, at most 5 minutes old, used once.
 */
public interface StepUpVerifier {

    /** @throws StepUpRequired when the proof is missing, stale, reused, or for someone else */
    void verify(String userId, @Nullable String proof);
}
