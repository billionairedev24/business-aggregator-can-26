package ca.northline.mcp;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link AgentCalls} in Valkey / Redis ({@code northline.mcp.store=redis}), shared by every api replica. Keys
 * {@code nl:mcp:{…}}, every one with a TTL; no token, person or argument in clear (the gateway hashes them).
 */
final class RedisAgentCalls implements AgentCalls {

    private static final String PREFIX = "nl:mcp:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    RedisAgentCalls(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean allow(String key, int perMinute) {
        var bucket = PREFIX + "rate:" + key + ":" + clock.millis() / 60_000;
        var count = redis.opsForValue().increment(bucket);
        if (count != null && count == 1) {
            redis.expire(bucket, Duration.ofSeconds(90));
        }
        return count != null && count <= perMinute;
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, value, ttl));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + key));
    }

    @Override
    public boolean remove(String key) {
        return Boolean.TRUE.equals(redis.delete(PREFIX + key));
    }
}
