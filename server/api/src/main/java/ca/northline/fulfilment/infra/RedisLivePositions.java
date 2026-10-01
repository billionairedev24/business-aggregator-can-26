package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.api.CourierLocations.Position;
import ca.northline.fulfilment.api.CourierLocations.Subscription;
import ca.northline.fulfilment.application.LivePositions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LivePositions} on Valkey ({@code northline.live.bus=redis}): the latest position per courier under
 * {@code nl:courier-pos:<courierId>} with a TTL (no history: each ping replaces it), the ping rate limit as
 * {@code SET nl:courier-ping:<courierId> NX PX <interval>}, and "moved" on channel {@code nl:courier:<orderId>}, which
 * every replica subscribes to and fans out to its own open tracking streams. A Valkey failure loses a live hint, never
 * the caller's request.
 */
@Slf4j
class RedisLivePositions implements LivePositions, AutoCloseable {

    static final String POSITION = "nl:courier-pos:";
    static final String PING = "nl:courier-ping:";
    static final String CHANNEL = "nl:courier:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final Map<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    RedisLivePositions(StringRedisTemplate redis, RedisConnectionFactory connections) {
        this.redis = redis;
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(
                (message, _) -> {
                    var orderId = new String(message.getBody(), StandardCharsets.UTF_8);
                    listeners.getOrDefault(orderId, Set.of()).forEach(Runnable::run);
                },
                new PatternTopic(CHANNEL + "*"));
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public boolean allow(String courierId, Duration interval) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PING + courierId, "1", interval));
    }

    @Override
    public void put(String courierId, Position position, Duration ttl) {
        redis.opsForValue().set(POSITION + courierId, JSON.writeValueAsString(position), ttl);
    }

    @Override
    public Optional<Position> latest(String courierId) {
        try {
            var json = redis.opsForValue().get(POSITION + courierId);
            return json == null ? Optional.empty() : Optional.of(JSON.readValue(json, Position.class));
        } catch (RuntimeException e) {
            log.warn("Courier position of {} unreadable: {}", courierId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void moved(String orderId) {
        try {
            redis.convertAndSend(CHANNEL + orderId, orderId);
        } catch (RuntimeException e) { // a live hint: the page refetches every 30 s anyway
            log.warn("Courier move for order {} not published: {}", orderId, e.getMessage());
        }
    }

    @Override
    public Subscription subscribe(String orderId, Runnable onMove) {
        listeners.computeIfAbsent(orderId, _ -> ConcurrentHashMap.newKeySet()).add(onMove);
        return () -> listeners.computeIfPresent(orderId, (_, set) -> {
            set.remove(onMove);
            return set.isEmpty() ? null : set;
        });
    }

    @Override
    public void close() {
        container.stop();
        try {
            container.destroy();
        } catch (Exception e) {
            log.debug("Courier position listener container did not close cleanly: {}", e.getMessage());
        }
    }
}
