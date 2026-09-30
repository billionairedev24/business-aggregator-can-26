package ca.northline.hire.application;

/**
 * 403 {@code step_up_required}: paying from a session signed in with a phone code alone needs a second factor first —
 * the customer confirms with a passkey or authenticator app ({@code POST /api/auth/step-up/*}) and retries with the
 * proof in {@code X-Step-Up} (S-55; docs/DECISIONS.md).
 */
public final class SecondFactorRequired extends RuntimeException {
    public SecondFactorRequired() {
        super("Confirm it's you with your passkey or authenticator app to pay.");
    }
}
