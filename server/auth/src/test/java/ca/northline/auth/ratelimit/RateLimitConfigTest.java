package ca.northline.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ca.northline.auth.application.LimitScope;
import ca.northline.auth.application.LimitedAction;
import ca.northline.auth.application.RateLimitProperties;
import ca.northline.auth.application.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Valkey by default; the in-memory fallback says so loudly and never runs under staging/prod. */
@ExtendWith(OutputCaptureExtension.class)
class RateLimitConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void valkeyByDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(RedisRateLimiter.class));
    }

    @Test
    void memory_forLocalRuns_isLogged(CapturedOutput output) {
        runner.withPropertyValues("northline.auth.rate-limits.store=memory").run(ctx -> {
            assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(InMemoryRateLimiter.class);
            assertThat(output).contains("Rate limits (S-9) are kept IN MEMORY");
        });
    }

    @Test
    void limitsCanBeOverriddenByEnvironmentVariables() {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(
                                        "NORTHLINE_AUTH_RATELIMITS_LIMITS_OTPSEND_IP_MAX", "40",
                                        "NORTHLINE_AUTH_RATELIMITS_LIMITS_OTPSEND_IP_WINDOW", "2h")));
        var props = Binder.get(env)
                .bind("northline.auth.rate-limits", RateLimitProperties.class)
                .get();
        var rule = props.limits().get(LimitedAction.OTP_SEND).get(LimitScope.IP);
        assertThat(rule.max()).isEqualTo(40);
        assertThat(rule.window()).isEqualTo(Duration.ofHours(2));
        assertThat(rule.lockout()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void memory_isRefusedUnderProd() {
        runner.withPropertyValues("northline.auth.rate-limits.store=memory", "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("not allowed under staging/prod"));
    }
}
