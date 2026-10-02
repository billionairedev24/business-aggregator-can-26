package ca.northline.worker.push;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One provider's pause after it throttled or failed (per worker replica): the provider's {@code Retry-After} when it
 * sent one, else {@code initial} doubled for each failure in a row, at most {@code max}. While paused, pushes for that
 * platform aren't attempted — they fail at once and are retried by the event's retry topics or the deferred job.
 */
final class Backoff {

    private final Duration initial;
    private final Duration max;
    private @Nullable Instant until;
    private int failures;

    Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    /** What is left of the pause at {@code now}; null when not paused. */
    synchronized @Nullable Duration remaining(Instant now) {
        var end = until;
        return end == null || !now.isBefore(end) ? null : Duration.between(now, end);
    }

    /** Pauses after a throttle or failure; returns how long. */
    synchronized Duration failed(Instant now, @Nullable Duration retryAfter) {
        var exponential = initial.multipliedBy(1L << Math.min(failures, 16));
        var wait = retryAfter != null ? retryAfter : exponential;
        if (wait.compareTo(max) > 0) {
            wait = max;
        }
        failures++;
        until = now.plus(wait);
        return wait;
    }

    synchronized void succeeded() {
        failures = 0;
        until = null;
    }
}
