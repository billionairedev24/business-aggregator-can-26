package ca.northline.orders.persistence;

import ca.northline.orders.application.TrackingBus;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * {@link TrackingBus} over Redis pub/sub (CLAUDE.md: order tracking pub/sub): channel {@code nl:order:<id>}, message
 * = the order id. Every replica subscribes to the pattern and wakes its own open tracking streams; nothing is stored.
 */
@Component
@Profile("!local & !test")
class RedisTrackingBus implements TrackingBus, AutoCloseable {

    static final String PREFIX = "nl:order:";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final Map<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    RedisTrackingBus(StringRedisTemplate redis, RedisConnectionFactory connections) {
        this.redis = redis;
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(
                (message, _) -> {
                    var orderId = new String(message.getBody(), StandardCharsets.UTF_8);
                    listeners.getOrDefault(orderId, Set.of()).forEach(Runnable::run);
                },
                new PatternTopic(PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public void changed(String orderId) {
        redis.convertAndSend(PREFIX + orderId, orderId);
    }

    @Override
    public Subscription subscribe(String orderId, Runnable onChange) {
        listeners.computeIfAbsent(orderId, _ -> ConcurrentHashMap.newKeySet()).add(onChange);
        return () -> listeners.computeIfPresent(orderId, (_, set) -> {
            set.remove(onChange);
            return set.isEmpty() ? null : set;
        });
    }

    @Override
    public void close() throws Exception {
        container.stop();
        container.destroy();
    }
}
