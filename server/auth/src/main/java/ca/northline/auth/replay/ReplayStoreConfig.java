package ca.northline.auth.replay;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code northline.auth.replay.store} ({@code REPLAY_STORE}): {@code redis} (Valkey, shared by every instance; the
 * default and the only choice under staging/prod) or {@code memory} (one instance: local runs and tests).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReplayStoreConfig.Properties.class)
public class ReplayStoreConfig {

    /** Where one-time ids and shared values live. */
    public enum Store {
        REDIS,
        MEMORY
    }

    /** {@code northline.auth.replay.*}. */
    @ConfigurationProperties("northline.auth.replay")
    public record Properties(@DefaultValue("redis") Store store) {}

    @Bean
    ReplayStore replayStore(
            Properties props, ObjectProvider<RedisConnectionFactory> redis, Clock clock, Environment environment) {
        return switch (props.store()) {
            case REDIS -> {
                log.info("One-time ids and DPoP nonces (S-29) in Valkey/Redis; unreachable = requests that need them"
                        + " are refused (503)");
                yield new RedisReplayStore(new StringRedisTemplate(redis.getObject()));
            }
            case MEMORY -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("northline.auth.replay.store=memory is not allowed under"
                            + " staging/prod: every instance must see every used proof and assertion id (Valkey)");
                }
                log.warn("One-time ids and DPoP nonces (S-29) are kept IN MEMORY: per instance — local development"
                        + " and tests only. Use the `valkey` profile (or northline.auth.replay.store=redis) to share"
                        + " them.");
                yield new InMemoryReplayStore(clock);
            }
        };
    }
}
