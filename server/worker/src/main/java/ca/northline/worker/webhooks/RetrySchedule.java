package ca.northline.worker.webhooks;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * When a failed delivery is tried again: exponential back-off with a cap and a little jitter (so a recovering endpoint
 * isn't hit by every queued delivery in the same second), and after how many attempts it fails for good.
 */
public final class RetrySchedule {

    private final WebhookProperties.Retry policy;
    private final RandomGenerator random;

    public RetrySchedule(WebhookProperties.Retry policy, RandomGenerator random) {
        if (policy.maxAttempts() < 1 || policy.multiplier() < 1 || policy.jitter() < 0 || policy.jitter() >= 1) {
            throw new IllegalArgumentException("Invalid webhook retry policy " + policy);
        }
        this.policy = policy;
        this.random = random;
    }

    /** The wait after the {@code attempts}-th failed attempt, or empty when that was the last one. */
    public Optional<Duration> after(int attempts) {
        if (attempts >= policy.maxAttempts()) {
            return Optional.empty();
        }
        return Optional.of(jittered(nominal(attempts)));
    }

    /** The wait after the {@code attempts}-th failure, without jitter. */
    public Duration nominal(int attempts) {
        var millis = (double) policy.initial().toMillis() * Math.pow(policy.multiplier(), Math.max(0, attempts - 1));
        return Duration.ofMillis(
                (long) Math.min(millis, (double) policy.maxDelay().toMillis()));
    }

    /** How long a delivery that keeps failing is retried: the sum of every wait. */
    public Duration span() {
        var total = Duration.ZERO;
        for (var n = 1; n < policy.maxAttempts(); n++) {
            total = total.plus(nominal(n));
        }
        return total;
    }

    private Duration jittered(Duration delay) {
        if (policy.jitter() == 0) {
            return delay;
        }
        var factor = 1 + (random.nextDouble() * 2 - 1) * policy.jitter();
        return Duration.ofMillis(Math.round(delay.toMillis() * factor));
    }
}
