package ca.northline.payments.persistence;

import ca.northline.payments.application.IdempotencyStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys in Redis (CLAUDE.md: "stored in Redis 24 h"): hash {@code nl:idem:<scope>:<key>} with fingerprint,
 * status and body; the claim is a {@code HSETNX} on the fingerprint plus an expiry.
 */
@Repository
@Profile("!local & !test")
@RequiredArgsConstructor
class PaymentsIdempotencyRedisStore implements IdempotencyStore {

    private final StringRedisTemplate redis;

    private static String redisKey(String scope, String key) {
        return "nl:idem:" + scope + ":" + key;
    }

    @Override
    public Optional<Stored> claim(String scope, String key, String fingerprint, Duration ttl) {
        var k = redisKey(scope, key);
        var hash = redis.opsForHash();
        if (Boolean.TRUE.equals(hash.putIfAbsent(k, "fingerprint", fingerprint))) {
            redis.expire(k, ttl);
            return Optional.empty();
        }
        Map<Object, Object> entry = hash.entries(k);
        var status = entry.get("status");
        var body = entry.get("body");
        return Optional.of(new Stored(
                String.valueOf(entry.getOrDefault("fingerprint", "")),
                status == null ? null : Integer.valueOf(status.toString()),
                body == null ? null : body.toString()));
    }

    @Override
    public void complete(String scope, String key, int status, String body) {
        redis.opsForHash().putAll(redisKey(scope, key), Map.of("status", String.valueOf(status), "body", body));
    }

    @Override
    public void release(String scope, String key) {
        var k = redisKey(scope, key);
        if (!redis.opsForHash().hasKey(k, "status")) {
            redis.delete(k);
        }
    }
}
