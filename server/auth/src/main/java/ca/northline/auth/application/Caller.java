package ca.northline.auth.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Who asks for a Settings › Security change (S-19): the signed-in person, the session (sign-in) their request comes
 * from, and when they last used a second factor in it (step-up).
 *
 * @param sessionId {@code identity.sessions} id; null for auth sessions created before S-19
 * @param lastSecondFactorAt null when the session has no second factor
 */
public record Caller(
        String userId, @Nullable String sessionId, @Nullable Instant lastSecondFactorAt) {}
