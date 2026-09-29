package ca.northline.auth.application;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.rate-limits.*} (S-9): per action and scope, how many attempts in a sliding window before a
 * lockout. Every lockout of the same subject doubles the previous one ({@code lockout × 2^(n-1)}, capped at
 * {@code max-lockout}) while the subject is remembered ({@code backoff-memory}). An action or scope without a rule is
 * not limited.
 *
 * @param store {@code redis} (Valkey/Redis, shared by every instance) or {@code memory} (one instance; local/test only)
 * @param backoffMemory how long earlier lockouts count towards the next one's length
 * @param limits action → scope → rule
 */
@ConfigurationProperties("northline.auth.rate-limits")
public record RateLimitProperties(
        @DefaultValue("redis") Store store,
        @DefaultValue("24h") Duration backoffMemory,
        @DefaultValue Map<LimitedAction, Map<LimitScope, Rule>> limits) {

    /** Where the counters live. */
    public enum Store {
        REDIS,
        MEMORY
    }

    /**
     * @param max requests allowed ({@code REQUESTS} actions) or failures that lock ({@code FAILURES} actions) per window
     * @param window sliding window
     * @param lockout first lockout
     * @param maxLockout longest lockout
     */
    public record Rule(
            int max,
            Duration window,
            @DefaultValue("15m") Duration lockout,
            @DefaultValue("24h") Duration maxLockout) {

        public Rule {
            if (max < 1 || window.isNegative() || window.isZero() || lockout.isNegative() || lockout.isZero()) {
                throw new IllegalArgumentException("Rate limit rules need max ≥ 1 and positive durations");
            }
            if (maxLockout.compareTo(lockout) < 0) {
                maxLockout = lockout;
            }
        }
    }
}
