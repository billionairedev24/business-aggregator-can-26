package ca.northline.auth.domain;

import java.io.Serializable;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-154: the Create account form was sent from this browser and its email code is awaited. {@code pendingId} is null
 * when the address already had an account (it was told so by email instead) — the flow looks the same from outside, and
 * every code typed for it is wrong.
 *
 * @param sentAt when the last email went out (resend cool-down)
 */
public record SignUp(@Nullable String pendingId, String email, App app, Instant sentAt) implements Serializable {

    public SignUp resent(Instant at) {
        return new SignUp(pendingId, email, app, at);
    }
}
