package ca.northline.auth.ratelimit;

import ca.northline.auth.application.RateLimitProperties;
import ca.northline.auth.application.RateLimiter;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code northline.auth.rate-limits.store}: {@code redis} (default; the cloud profiles and {@code local,valkey}) or
 * {@code memory} ({@code local}, {@code test}). Memory is refused under staging/prod, where several instances run.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    static final String STORE = "northline.auth.rate-limits.store";

    @Bean
    @ConditionalOnProperty(name = STORE, havingValue = "redis", matchIfMissing = true)
    RateLimiter redisRateLimiter(RedisConnectionFactory redis, Environment environment) {
        log.info(
                "Rate limits (S-9) in Valkey/Redis at {}:{}",
                environment.getProperty("spring.data.redis.host"),
                environment.getProperty("spring.data.redis.port"));
        return new RedisRateLimiter(new StringRedisTemplate(redis));
    }

    @Bean
    @ConditionalOnProperty(name = STORE, havingValue = "memory")
    RateLimiter inMemoryRateLimiter(Clock clock, Environment environment) {
        if (environment.matchesProfiles("staging | prod")) {
            throw new IllegalStateException(
                    "northline.auth.rate-limits.store=memory is not allowed under staging/prod: limits must be shared"
                            + " by every instance (Valkey)");
        }
        log.warn("Rate limits (S-9) are kept IN MEMORY: per instance and lost on restart — local development and"
                + " tests only. Use the `valkey` profile (or northline.auth.rate-limits.store=redis) to share them.");
        return new InMemoryRateLimiter(clock);
    }
}
