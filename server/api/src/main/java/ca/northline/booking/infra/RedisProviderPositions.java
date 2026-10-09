package ca.northline.booking.infra;

import ca.northline.booking.application.ProviderPositions;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ProviderPositions} on Valkey ({@code northline.live.bus=redis}): the latest position per booking under
 * {@code nl:visit-pos:<bookingId>} with a TTL, the rate limit as {@code SET nl:visit-ping:<bookingId> NX PX}. A Valkey
 * failure loses a live hint, never the provider's request.
 */
@Slf4j
@RequiredArgsConstructor
class RedisProviderPositions implements ProviderPositions {

    static final String POSITION = "nl:visit-pos:";
    static final String PING = "nl:visit-ping:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    @Override
    public boolean allow(String bookingId, Duration interval) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PING + bookingId, "1", interval));
    }

    @Override
    public void put(String bookingId, Position position, Duration ttl) {
        redis.opsForValue().set(POSITION + bookingId, JSON.writeValueAsString(position), ttl);
    }

    @Override
    public Optional<Position> latest(String bookingId) {
        try {
            var json = redis.opsForValue().get(POSITION + bookingId);
            return json == null ? Optional.empty() : Optional.of(JSON.readValue(json, Position.class));
        } catch (RuntimeException e) {
            log.warn("Provider position of booking {} unreadable: {}", bookingId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void clear(String bookingId) {
        try {
            redis.delete(POSITION + bookingId);
        } catch (RuntimeException e) {
            log.warn("Provider position of booking {} not cleared (it expires anyway): {}", bookingId, e.getMessage());
        }
    }
}
