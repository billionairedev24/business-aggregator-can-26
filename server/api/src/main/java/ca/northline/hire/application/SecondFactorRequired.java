package ca.northline.hire.application;

/**
 * Paying needs a second factor first — the S-51 rule, reused for bookings and quotes: 403 with {@code code}
 * {@code step_up_required} (a phone-code sign-in confirms with the account's passkey or authenticator and retries with
 * the proof in {@code X-Step-Up}) or {@code second_factor_required} (the account has neither: enrol a passkey, which
 * issues the proof too).
 */
public final class SecondFactorRequired extends RuntimeException {

    private final String code;

    SecondFactorRequired(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
