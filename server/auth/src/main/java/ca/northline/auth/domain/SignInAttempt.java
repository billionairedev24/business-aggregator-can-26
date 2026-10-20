package ca.northline.auth.domain;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * "Email or mobile" entered on the sign-in form, waiting for its second factor. {@code userId} is null when no account
 * matches — the flow carries on identically so the form never reveals whether an account exists.
 *
 * @param passwordVerified S-154: the account's password was given in this attempt (email + password sign-in); an
 *     account that has a password gets its authenticator or backup code accepted only then
 */
public record SignInAttempt(String identifier, @Nullable String userId, int failedAttempts, boolean passwordVerified)
        implements Serializable {

    public static final int MAX_FAILED = 5;

    /** Identifier-first (no password yet). */
    public SignInAttempt(String identifier, @Nullable String userId, int failedAttempts) {
        this(identifier, userId, failedAttempts, false);
    }

    public SignInAttempt failed() {
        return new SignInAttempt(identifier, userId, failedAttempts + 1, passwordVerified);
    }

    public boolean locked() {
        return failedAttempts >= MAX_FAILED;
    }
}
