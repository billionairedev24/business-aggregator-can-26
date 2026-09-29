package ca.northline.developer.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A merchant API key as listed (never the secret). {@code prefix} is the start of the secret for recognition.
 *
 * @param rateLimit requests per minute
 */
public record ApiKey(
        String id,
        String merchantId,
        String name,
        List<String> scopes,
        String prefix,
        int rateLimit,
        Instant createdAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant revokedAt) {

    public static final int DEFAULT_RATE_LIMIT = 600;

    public ApiKey {
        scopes = List.copyOf(scopes);
    }

    public boolean active() {
        return revokedAt == null;
    }

    /** A key issued now; the caller keeps {@code secret} to show it once. */
    public record Issued(ApiKey key, String secret) {}
}
