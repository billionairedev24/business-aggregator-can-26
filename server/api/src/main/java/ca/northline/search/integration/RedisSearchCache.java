package ca.northline.search.integration;

import ca.northline.search.application.SearchCache;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The hot-query cache in Redis/Valkey, shared by the api's replicas: {@code nl:search:*} keys with a TTL. */
final class RedisSearchCache implements SearchCache {

    private final StringRedisTemplate redis;

    RedisSearchCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String json, Duration ttl) {
        redis.opsForValue().set(key, json, ttl);
    }
}
