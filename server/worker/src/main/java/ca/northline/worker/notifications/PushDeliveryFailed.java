package ca.northline.worker.notifications;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The push provider can't take a push now — throttled (APNs 429, FCM {@code QUOTA_EXCEEDED}), down (5xx, time-out) or
 * backing off after one of those. The claim is released and the push is retried: the event's retry topics, or the
 * deferred-notification job. Dead tokens are not this: their devices are deleted and the push counts as sent to the
 * others.
 */
public class PushDeliveryFailed extends RuntimeException {

    private final @Nullable Duration retryAfter;

    public PushDeliveryFailed(String message, @Nullable Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** What the provider asked for ({@code Retry-After}) or the back-off left, when known. */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
