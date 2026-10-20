package ca.northline.auth.domain;

import java.io.Serializable;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-154: "Forgot password" was asked for this address from this browser. {@code userId} is null when no account has
 * it: nothing was sent, the flow looks the same, and every code typed for it is wrong.
 */
public record ResetRequest(String email, @Nullable String userId, App app, Instant sentAt) implements Serializable {

    public ResetRequest resent(Instant at) {
        return new ResetRequest(email, userId, app, at);
    }
}
