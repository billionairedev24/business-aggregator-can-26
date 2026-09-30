package ca.northline.developer.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One event sent (or being sent) to one endpoint, as the Studio's delivery log shows it (S-33). The worker writes it;
 * the Studio can resend it or send a test event.
 *
 * @param state {@code pending} (waiting for its next attempt) | {@code succeeded} (a 2xx came back) | {@code failed}
 *     (attempts exhausted, or the endpoint was turned off)
 * @param eventType the public webhook type ({@code booking.completed}, {@code webhook.test}, …)
 * @param attempts HTTP attempts made so far
 * @param statusCode the latest attempt's HTTP status, null when none came back (timeout, refused address, …)
 * @param responseSnippet the first characters of the latest answer (control characters removed)
 * @param resendOf the delivery this one resends, null for the original
 * @param history every attempt, newest first
 */
public record WebhookDelivery(
        String id,
        String endpointId,
        String eventId,
        @Nullable String eventType,
        String state,
        int attempts,
        @Nullable Integer statusCode,
        @Nullable Instant lastAttemptAt,
        @Nullable Instant nextAttemptAt,
        @Nullable Integer durationMs,
        @Nullable String error,
        @Nullable String responseSnippet,
        boolean test,
        @Nullable String resendOf,
        Instant createdAt,
        List<Attempt> history) {

    public static final String PENDING = "pending";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String TEST_EVENT = "webhook.test";

    public WebhookDelivery {
        history = List.copyOf(history);
    }

    public boolean pending() {
        return PENDING.equals(state);
    }

    /** One HTTP try. */
    public record Attempt(
            int attempt,
            Instant at,
            @Nullable Integer statusCode,
            @Nullable Integer durationMs,
            @Nullable String error,
            @Nullable String responseSnippet) {}
}
