package ca.northline.auth.domain;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * "Email or mobile" entered on the sign-in form, waiting for its second factor. {@code userId} is null when no account
 * matches — the flow carries on identically so the form never reveals whether an account exists.
 */
public record SignInAttempt(String identifier, @Nullable String userId, int failedAttempts) implements Serializable {

    public static final int MAX_FAILED = 5;

    public SignInAttempt failed() {
        return new SignInAttempt(identifier, userId, failedAttempts + 1);
    }

    public boolean locked() {
        return failedAttempts >= MAX_FAILED;
    }
}
