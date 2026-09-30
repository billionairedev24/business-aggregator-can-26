package ca.northline.auth.application;

import java.util.Locale;

/**
 * What the rate limits count (S-9). {@link Kind#REQUESTS} limits every call (sending codes, looking up an identifier);
 * {@link Kind#FAILURES} limits wrong answers only and is reset for the account and the session by a success.
 */
public enum LimitedAction {
    /** A phone code sent or re-sent, by SMS or voice (registration). */
    OTP_SEND(Kind.REQUESTS, true),
    /** A wrong phone code (registration). */
    OTP_VERIFY(Kind.FAILURES, true),
    /** "Email or mobile" submitted on the sign-in form. */
    SIGN_IN_LOOKUP(Kind.REQUESTS, false),
    /** A wrong authenticator code at sign-in. */
    TOTP_VERIFY(Kind.FAILURES, true),
    /** A wrong or used backup code at sign-in. */
    BACKUP_CODE_VERIFY(Kind.FAILURES, true),
    /** A passkey assertion that didn't verify (or belongs to someone else) at sign-in. */
    PASSKEY_ASSERTION(Kind.FAILURES, true),
    /** A failed step-up confirmation (payouts), passkey or authenticator code. */
    STEP_UP(Kind.FAILURES, true),
    /** S-19: revoking sessions or removing a passkey (Settings › Security) — every call counts. */
    SECURITY_CHANGE(Kind.REQUESTS, false);

    /** Whether every call counts, or only failures. */
    public enum Kind {
        REQUESTS,
        FAILURES
    }

    private final Kind kind;
    private final boolean guardsSecret;

    LimitedAction(Kind kind, boolean guardsSecret) {
        this.kind = kind;
        this.guardsSecret = guardsSecret;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * S-20: a code/OTP or factor path — something that can be guessed. These are refused (503) while the limit store is
     * unreachable and {@code northline.auth.rate-limits.when-unavailable} is {@code closed}; the identifier lookup and
     * the signed-in Security changes (which already need a recent second factor) keep going.
     */
    public boolean guardsSecret() {
        return guardsSecret;
    }

    /** {@code otp_send} — used in keys, logs and the audit log. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
