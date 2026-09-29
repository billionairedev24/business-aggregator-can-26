package ca.northline.auth.application;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/** The sign-in / registration flow can't take this step right now (answered as a ProblemDetail with {@code code}). */
@Getter
public final class FlowRejected extends RuntimeException {

    /** Why — decides the HTTP status. */
    public enum Reason {
        /** No registration / sign-in in progress (session expired or steps out of order) → 409. */
        NOT_STARTED("flow_not_started"),
        /** Resend requested inside the 45 s cool-down → 429 with retryAfterSeconds. */
        THROTTLED("otp_throttled"),
        /** Too many wrong codes → 429. */
        LOCKED("too_many_attempts"),
        /** Needs a signed-in session with a second factor → 401. */
        UNAUTHENTICATED("unauthenticated");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final @Nullable Long retryAfterSeconds;

    public FlowRejected(Reason reason, String message) {
        this(reason, message, null);
    }

    public FlowRejected(Reason reason, String message, @Nullable Long retryAfterSeconds) {
        super(message);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
