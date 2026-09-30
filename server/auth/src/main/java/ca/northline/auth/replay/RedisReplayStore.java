package ca.northline.auth.replay;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Valkey/Redis: {@code nl:auth-replay:<key>} — SET NX with a TTL for one-time ids, SET NX then GET for shared values
 * (so every instance agrees on one random value). Callers pass hashes, never a proof or an assertion.
 */
final class RedisReplayStore implements ReplayStore {

    static final String PREFIX = "nl:auth-replay:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    RedisReplayStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean firstUse(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, "1", ttl));
        } catch (DataAccessException e) {
            throw new Unavailable(e);
        }
    }

    @Override
    public String shared(String key, Duration ttl) {
        try {
            var ops = redis.opsForValue();
            ops.setIfAbsent(PREFIX + key, random(), ttl);
            return Objects.requireNonNull(ops.get(PREFIX + key), "value just written");
        } catch (DataAccessException e) {
            throw new Unavailable(e);
        }
    }

    static String random() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
