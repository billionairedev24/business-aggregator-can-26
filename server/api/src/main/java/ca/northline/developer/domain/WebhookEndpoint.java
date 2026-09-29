package ca.northline.developer.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A partner endpoint receiving HMAC-SHA256 signed events, with its latest delivery ("last delivery 200 OK").
 *
 * @param lastStatus HTTP status of the latest delivery attempt, null before the first
 */
public record WebhookEndpoint(
        String id,
        String merchantId,
        String url,
        List<String> events,
        boolean active,
        Instant createdAt,
        @Nullable Integer lastStatus,
        @Nullable Instant lastDeliveryAt) {

    public static final String SIGNATURE = "HMAC-SHA256";

    public WebhookEndpoint {
        events = List.copyOf(events);
    }

    /** An endpoint created (or re-keyed) now; {@code secret} is shown once. */
    public record WithSecret(WebhookEndpoint endpoint, String secret) {}
}
