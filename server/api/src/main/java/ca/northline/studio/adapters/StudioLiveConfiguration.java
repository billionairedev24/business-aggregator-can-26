package ca.northline.studio.adapters;

import ca.northline.studio.application.LiveProperties;
import ca.northline.studio.application.StudioLive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S-68: which {@link StudioLive} runs — {@code northline.live.bus} ({@code LIVE_BUS}): {@code memory} (local, test) or
 * {@code redis} (Valkey pub/sub, the cloud default). {@code memory} is refused under staging/prod, where more than one
 * api replica holds streams (docs/runbooks/README.md).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveProperties.class)
class StudioLiveConfiguration {

    @Bean
    StudioLive studioLive(
            LiveProperties props,
            ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<RedisConnectionFactory> connections,
            Environment env) {
        return switch (props.bus()) {
            case MEMORY -> {
                if (env.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException(
                            "LIVE_BUS=memory is not allowed under staging/prod: every api replica"
                                    + " must see the Studio's live signals (LIVE_BUS=redis, docs/runbooks/README.md)");
                }
                yield new MemoryStudioLive();
            }
            case REDIS -> {
                log.info("Studio live signals over Valkey pub/sub ({}*)", RedisStudioLive.PREFIX);
                yield new RedisStudioLive(redis.getObject(), connections.getObject());
            }
        };
    }
}
