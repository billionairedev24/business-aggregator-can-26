package ca.northline.privacy.application;

/**
 * 403 {@code code=step_up_required}: the step-up proof is missing, stale or someone else's. Confirm with the passkey or
 * authenticator app (northline-auth {@code /api/auth/step-up/*}) and retry with the new proof in {@code X-Step-Up}.
 */
public final class IdentityCheckRequired extends RuntimeException {

    public static final String CODE = "step_up_required";
    public static final String MESSAGE = "Confirm it's you with your passkey or authenticator app, then try again.";

    IdentityCheckRequired() {
        super(MESSAGE);
    }
}
