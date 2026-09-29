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

    public static final String SIX_DIGITS = "^\\d{6}$";

    private AuthMessages() {}
}
