package ca.northline.auth.application;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Outbound port: sliding-window attempt counters with lockouts (Redis/Valkey in the cloud, memory for local runs). All
 * limits of one call are checked and recorded atomically. {@link #check} and {@link #record} throw {@link Unavailable}
 * when the store can't answer; {@link AttemptLimits} applies the configured policy (S-20).
 */
public interface RateLimiter {

    /** Denied when any of the limits is locked; records nothing. */
    Decision check(List<Limit> limits);

    /**
     * Denied when any limit is locked; otherwise records one attempt against each and locks those that reach their
     * threshold (the decision is then denied too, with {@link Decision#newlyLocked()}).
     */
    Decision record(List<Limit> limits);

    /** The counters can't be read or written right now (e.g. Valkey is down). */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Forgets the attempts and earlier lockouts of these subjects (after a success). */
    void reset(List<Limit> limits);

    /**
     * One counter.
     *
     * @param subject opaque, already hashed value (never an email or IP in clear)
     * @param threshold the attempt that locks: {@code max} for failures, {@code max + 1} for requests
     */
    record Limit(
            LimitedAction action,
            LimitScope scope,
            String subject,
            RateLimitProperties.Rule rule,
            int threshold,
            Duration backoffMemory) {}

    /** Outcome; {@code retryAfter} is zero when allowed. */
    record Decision(boolean allowed, Duration retryAfter, Set<LimitScope> newlyLocked) {

        public static final Decision ALLOWED = new Decision(true, Duration.ZERO, Set.of());

        public static Decision denied(Duration retryAfter, Set<LimitScope> newlyLocked) {
            return new Decision(false, retryAfter, Set.copyOf(newlyLocked));
        }
    }
}
