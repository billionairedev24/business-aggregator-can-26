package ca.northline.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only source of "now". Inject {@link Clock} (the outbound port) and call {@code clock.instant()}; never
 * {@code Instant.now()}. Tests replace it with a fixed/mutable clock bean.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
