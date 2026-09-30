package ca.northline.auth.domain;

/**
 * User-facing messages. The registration ones are copied exactly from {@code docs/spec/validation-rules.md}
 * § Registration; the rest were not in the spec and are recorded in {@code docs/DECISIONS.md} (Auth workstream).
 */
public final class AuthMessages {

    // validation-rules.md § Registration (exact)
    public static final String FIRST_NAME_REQUIRED = "First name is required.";
    public static final String LAST_NAME_REQUIRED = "Last name is required.";
    public static final String PHONE_REQUIRED = "Mobile number is required for verification.";
    public static final String PHONE_FORMAT = "Enter a valid Canadian mobile, e.g. +1 403 555 0148.";
    public static final String EMAIL_REQUIRED = "Email is required.";
    public static final String EMAIL_FORMAT = "That doesn't look like an email address.";
    public static final String TERMS_REQUIRED = "You need to accept the Terms and Privacy Policy.";

    // Not in the spec (DECISIONS.md)
    public static final String EMAIL_TAKEN = "An account already uses this email. Sign in instead.";
    public static final String PHONE_TAKEN = "An account already uses this mobile number. Sign in instead.";
    public static final String CODE_REQUIRED = "Enter the 6-digit code.";
    public static final String CODE_FORMAT = "The code is 6 digits.";
    public static final String CODE_WRONG = "That code doesn't match. Check it and try again.";
    public static final String CODE_EXPIRED = "That code has expired. Send a new one.";
    public static final String CODE_LOCKED = "Too many tries. Send a new code.";
    public static final String IDENTIFIER_REQUIRED = "Enter your email or mobile.";
    public static final String SIGN_IN_CODE_WRONG = "That code didn't work. Check it and try again.";
    public static final String BACKUP_CODE_REQUIRED = "Enter one of your backup codes.";
    public static final String BACKUP_CODE_WRONG = "That backup code didn't work, or it was already used.";
    public static final String PASSKEY_FAILED = "That passkey couldn't be verified. Try again or use another method.";
    public static final String TOO_MANY_ATTEMPTS = "Too many attempts. Start again in a few minutes.";
    /** S-9 rate limits; the Studio shows its own (translated) copy with a countdown from retryAfterSeconds. */
    public static final String RATE_LIMITED = "Too many attempts. Wait a moment and try again.";
    /** S-8: the provider didn't take the first code (the Studio shows its own translated copy by {@code code}). */
    public static final String CODE_NOT_SENT_FORM =
            "We couldn't send a code to this number right now. Try again in a moment.";
    /** S-8: a resent text message wasn't taken. */
    public static final String CODE_NOT_SENT =
            "We couldn't send the text message. Try again in a moment, or choose Call me instead.";
    /** S-8: the voice call couldn't be placed. */
    public static final String CALL_NOT_PLACED =
            "We couldn't call this number. Try again in a moment, or resend the code by text.";

    /** S-19: revoking a session / removing a passkey needs a second factor from the last few minutes. */
    public static final String STEP_UP_REQUIRED = "Confirm it's you to make this change.";
    /** S-19: the last passkey can't go while no authenticator app is set up (and vice versa). */
    public static final String LAST_FACTOR = "Add another passkey or an authenticator app before you remove this one.";

    public static final String SESSION_GONE = "That session has already ended.";
    public static final String PASSKEY_GONE = "That passkey was already removed.";
    public static final String CURRENT_SESSION = "This is the session you're using now. Sign out instead.";

    public static final String SIX_DIGITS = "^\\d{6}$";

    private AuthMessages() {}
}
