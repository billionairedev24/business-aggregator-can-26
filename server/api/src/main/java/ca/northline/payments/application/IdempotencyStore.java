package ca.northline.payments.application;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: {@code Idempotency-Key} storage for money-moving POSTs (CLAUDE.md: stored 24 h — Redis in production,
 * the database under {@code local} / {@code test}). Also consumes one-time step-up proof ids.
 */
public interface IdempotencyStore {

    Duration TTL = Duration.ofHours(24);

    /** A stored key: {@code status == null} while the first request is still running. */
    record Stored(
            String fingerprint,
            @Nullable Integer status,
            @Nullable String body) {}

    /** Claims {@code (scope, key)}; empty when this call claimed it, the existing entry otherwise. */
    Optional<Stored> claim(String scope, String key, String fingerprint, Duration ttl);

    /** Stores the response of a claimed key. */
    void complete(String scope, String key, int status, String body);

    /** Frees a claimed key after a failure, so the client can retry with it. */
    void release(String scope, String key);
}
