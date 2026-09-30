package ca.northline.auth.dpop;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Valkey/Redis: {@code nl:auth-dpop:jti:<sha256>} (SET NX with a TTL) and {@code nl:auth-dpop:nonce:<window>} (SET NX
 * then GET, so every instance agrees on one random nonce per window). No proof content is stored, only hashes.
 */
final class RedisDpopState implements DpopState {

    static final String PREFIX = "nl:auth-dpop:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    RedisDpopState(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean firstUse(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + "jti:" + key, "1", ttl));
        } catch (DataAccessException e) {
            throw new Unavailable(e);
        }
    }

    @Override
    public String nonce(long window, Duration ttl) {
        var key = PREFIX + "nonce:" + window;
        try {
            var ops = redis.opsForValue();
            ops.setIfAbsent(key, randomNonce(), ttl);
            return Objects.requireNonNull(ops.get(key), "nonce just written");
        } catch (DataAccessException e) {
            throw new Unavailable(e);
        }
    }

    static String randomNonce() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
