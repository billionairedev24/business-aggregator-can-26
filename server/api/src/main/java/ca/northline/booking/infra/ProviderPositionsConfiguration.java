package ca.northline.booking.infra;

import ca.northline.booking.application.ProviderPositions;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Which {@link ProviderPositions} runs — the live switch every live feature shares ({@code northline.live.bus},
 * {@code LIVE_BUS}: {@code memory} for local and test, refused under staging/prod; {@code redis} = Valkey). No new
 * variable.
 */
@Configuration(proxyBeanMethods = false)
class ProviderPositionsConfiguration {

    @Bean
    ProviderPositions providerPositions(
            @Value("${northline.live.bus:memory}") String bus,
            ObjectProvider<StringRedisTemplate> redis,
            Clock clock,
            Environment env) {
        if (bus.toLowerCase(Locale.ROOT).equals("redis")) {
            return new RedisProviderPositions(redis.getObject());
        }
        if (env.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("LIVE_BUS=memory is not allowed under staging/prod: provider positions must"
                    + " reach every api replica (LIVE_BUS=redis)");
        }
        return new MemoryProviderPositions(clock);
    }
}
