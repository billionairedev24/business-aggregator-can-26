package ca.northline.orders.application;

/**
 * Checkout needs a second factor first (S-51): 403 with {@code code} {@code step_up_required} (confirm with the
 * account's passkey or authenticator, send the proof as {@code X-Step-Up}) or {@code second_factor_required} (the
 * account has none: enrol a passkey, which also gives the proof).
 */
public final class StepUpNeeded extends RuntimeException {

    private final String code;

    StepUpNeeded(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
