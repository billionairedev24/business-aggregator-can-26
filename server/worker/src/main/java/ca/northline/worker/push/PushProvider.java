package ca.northline.worker.push;

import ca.northline.worker.notifications.PushSender;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** One push service for one platform: APNs for {@code ios}, FCM for {@code android}. */
public interface PushProvider {

    /** {@code ios} | {@code android}: the devices it serves. */
    String platform();

    /** Sends one push to one installation; never throws for an answer from the service. */
    Outcome send(PushDevice device, PushSender.Content words, PushSender.PushMessage message);

    /** What the service answered. */
    sealed interface Outcome {

        record Delivered() implements Outcome {}

        /** The token is dead (uninstalled, expired, another app's or project's): delete the device. */
        record InvalidToken(String reason) implements Outcome {}

        /** Too many requests: pause this provider ({@code Retry-After} when the service gave one). */
        record Throttled(@Nullable Duration retryAfter) implements Outcome {}

        /** The service is down, timed out or refused our credentials: pause and retry later. */
        record Unavailable(String reason, @Nullable Duration retryAfter) implements Outcome {}

        /** This push can never be sent (payload refused): a bug to fix, not retried. */
        record Rejected(String reason) implements Outcome {}
    }

    /** {@code Retry-After} in seconds or as an HTTP date; null when absent or unreadable. */
    static @Nullable Duration retryAfter(@Nullable String header, java.time.Instant now) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header.strip())));
        } catch (NumberFormatException e) {
            try {
                var at = java.time.ZonedDateTime.parse(
                        header.strip(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                var wait = Duration.between(now, at.toInstant());
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
