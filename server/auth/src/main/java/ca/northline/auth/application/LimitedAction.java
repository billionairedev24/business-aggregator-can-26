package ca.northline.auth.application;

import java.util.Locale;

/**
 * What the rate limits count (S-9). {@link Kind#REQUESTS} limits every call (sending codes, looking up an identifier);
 * {@link Kind#FAILURES} limits wrong answers only and is reset for the account and the session by a success.
 */
public enum LimitedAction {
    /** A phone code sent or re-sent, by SMS or voice (registration). */
    OTP_SEND(Kind.REQUESTS),
    /** A wrong phone code (registration). */
    OTP_VERIFY(Kind.FAILURES),
    /** "Email or mobile" submitted on the sign-in form. */
    SIGN_IN_LOOKUP(Kind.REQUESTS),
    /** A wrong authenticator code at sign-in. */
    TOTP_VERIFY(Kind.FAILURES),
    /** A wrong or used backup code at sign-in. */
    BACKUP_CODE_VERIFY(Kind.FAILURES),
    /** A passkey assertion that didn't verify (or belongs to someone else) at sign-in. */
    PASSKEY_ASSERTION(Kind.FAILURES),
    /** A failed step-up confirmation (payouts), passkey or authenticator code. */
    STEP_UP(Kind.FAILURES);

    /** Whether every call counts, or only failures. */
    public enum Kind {
        REQUESTS,
        FAILURES
    }

    private final Kind kind;

    LimitedAction(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** {@code otp_send} — used in keys, logs and the audit log. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
