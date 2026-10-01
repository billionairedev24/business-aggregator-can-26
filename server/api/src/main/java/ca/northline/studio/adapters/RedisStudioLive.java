package ca.northline.studio.adapters;

import ca.northline.studio.application.StudioLive;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * {@link StudioLive} over Valkey pub/sub ({@code northline.live.bus=redis}): channel {@code nl:studio:<merchantId>},
 * message {@code <topic>[:<id>]}. Every replica subscribes to the pattern and wakes its own open streams; nothing is
 * stored, so a signal published while no replica holds a stream for the business is simply dropped.
 */
@Slf4j
class RedisStudioLive implements StudioLive, AutoCloseable {

    static final String PREFIX = "nl:studio:";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final MemoryStudioLive.Listeners listeners = new MemoryStudioLive.Listeners();

    RedisStudioLive(StringRedisTemplate redis, RedisConnectionFactory connections) {
        this.redis = redis;
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(
                (message, _) -> {
                    var channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                    var signal = Signal.decode(new String(message.getBody(), StandardCharsets.UTF_8));
                    if (signal != null && channel.startsWith(PREFIX)) {
                        listeners.deliver(channel.substring(PREFIX.length()), signal);
                    }
                },
                new PatternTopic(PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public void signal(String merchantId, Signal signal) {
        try {
            redis.convertAndSend(PREFIX + merchantId, signal.encode());
        } catch (RuntimeException e) { // a live hint, never worth failing the caller: the Studio polls as a fallback
            log.warn("Studio live signal {} for {} not published: {}", signal.encode(), merchantId, e.getMessage());
        }
    }

    @Override
    public Subscription subscribe(String merchantId, Consumer<Signal> listener) {
        return listeners.add(merchantId, listener);
    }

    @Override
    public void close() {
        container.stop();
        try {
            container.destroy();
        } catch (Exception e) {
            log.debug("Studio live listener container did not close cleanly: {}", e.getMessage());
        }
    }
}
