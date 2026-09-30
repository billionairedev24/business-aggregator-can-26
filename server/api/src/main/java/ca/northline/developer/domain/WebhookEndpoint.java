package ca.northline.developer.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A partner endpoint receiving HMAC-SHA256 signed events, with its latest delivery ("last delivery 200 OK") and its
 * health as the webhook worker sees it (S-33).
 *
 * @param active false once the worker turned it off after sustained failure (the owner turns it back on)
 * @param lastStatus HTTP status of the latest delivery attempt, null before the first (or when none came back)
 * @param failingSince first failed attempt since the last success, null while healthy
 * @param disabledAt when the worker turned it off, null while active
 * @param previousSecretUntil until when the secret before the latest rotation still signs too, null when none does
 */
public record WebhookEndpoint(
        String id,
        String merchantId,
        String url,
        List<String> events,
        boolean active,
        Instant createdAt,
        @Nullable Integer lastStatus,
        @Nullable Instant lastDeliveryAt,
        @Nullable Instant failingSince,
        @Nullable Instant disabledAt,
        @Nullable Instant previousSecretUntil) {

    public static final String SIGNATURE = "HMAC-SHA256";

    public WebhookEndpoint {
        events = List.copyOf(events);
    }

    /** A new, healthy endpoint. */
    public static WebhookEndpoint created(String id, String merchantId, String url, List<String> events, Instant at) {
        return new WebhookEndpoint(id, merchantId, url, events, true, at, null, null, null, null, null);
    }

    /** An endpoint created (or re-keyed) now; {@code secret} is shown once. */
    public record WithSecret(WebhookEndpoint endpoint, String secret) {}
}
