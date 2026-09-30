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
        /** Over a rate limit per account, IP or session (S-9) → 429 with retryAfterSeconds and Retry-After. */
        RATE_LIMITED("rate_limited"),
        /** The SMS/voice provider didn't take the code (S-8) → 503 (detail says which channel failed). */
        CODE_NOT_SENT("code_not_sent"),
        /** Needs a signed-in session with a second factor → 401. */
        UNAUTHENTICATED("unauthenticated"),
        /** S-19: the change needs a second factor from the last few minutes (step-up) → 403. */
        STEP_UP_REQUIRED("step_up_required"),
        /** S-19: removing this factor would leave the account without a second factor → 409. */
        LAST_FACTOR("last_factor"),
        /** S-19: revoking the session the request comes from (sign out instead) → 409. */
        CURRENT_SESSION("current_session"),
        /** S-19: the session or passkey isn't there (any more) for this person → 404. */
        GONE("not_found"),
        /**
         * S-20: the rate-limit store is unreachable and codes / second factors fail closed → 503 with
         * retryAfterSeconds.
         */
        UNAVAILABLE("sign_in_unavailable");

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
