package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.application.LivePositions;
import java.time.Clock;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S-88: which {@link LivePositions} runs — the same switch as the Studio's live stream (S-68), {@code northline.live.bus}
 * ({@code LIVE_BUS}): {@code memory} (local, test; refused under staging/prod) or {@code redis} (Valkey, the cloud
 * default). No new variable.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class LivePositionsConfiguration {

    @Bean
    LivePositions livePositions(
            @Value("${northline.live.bus:memory}") String bus,
            ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<RedisConnectionFactory> connections,
            Clock clock,
            Environment env) {
        if (bus.toLowerCase(Locale.ROOT).equals("redis")) {
            log.info(
                    "Courier positions in Valkey ({}*, channel {}*)",
                    RedisLivePositions.POSITION,
                    RedisLivePositions.CHANNEL);
            return new RedisLivePositions(redis.getObject(), connections.getObject());
        }
        if (env.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("LIVE_BUS=memory is not allowed under staging/prod: courier positions must"
                    + " reach every api replica (LIVE_BUS=redis, docs/runbooks/fulfilment.md)");
        }
        return new MemoryLivePositions(clock);
    }
}
