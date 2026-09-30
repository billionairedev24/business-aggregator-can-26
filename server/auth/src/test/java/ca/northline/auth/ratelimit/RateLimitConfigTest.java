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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
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

    /** S-20: staging/prod fail closed while Valkey is down unless the operator opts out; everywhere else fails open. */
    @ParameterizedTest
    @CsvSource({"'', OPEN", "staging, CLOSED", "prod, CLOSED", "dev, OPEN", "local, OPEN"})
    void whenUnavailable_perProfile(String profile, RateLimitProperties.WhenUnavailable expected) throws Exception {
        assertThat(bindWithProfile(profile, Map.of()).whenUnavailable()).isEqualTo(expected);
    }

    @Test
    void whenUnavailable_canBeOpenedUnderProd_asABreakGlass(CapturedOutput output) throws Exception {
        assertThat(bindWithProfile("prod", Map.of("RATE_LIMIT_WHEN_UNAVAILABLE", "open"))
                        .whenUnavailable())
                .isEqualTo(RateLimitProperties.WhenUnavailable.OPEN);
        runner.withPropertyValues("northline.auth.rate-limits.when-unavailable=open", "spring.profiles.active=prod")
                .run(ctx -> assertThat(output).contains("RATE_LIMIT_WHEN_UNAVAILABLE=open under staging/prod"));
    }

    /** application.yml (+ application-{profile}.yml on top) as the app would bind it, with these variables. */
    private static RateLimitProperties bindWithProfile(String profile, Map<String, Object> variables) throws Exception {
        var env = new StandardEnvironment();
        var sources = env.getPropertySources();
        sources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        var loader = new YamlPropertySourceLoader();
        if (!profile.isEmpty()) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml"))
                    .forEach(sources::addLast);
        }
        loader.load("base", new ClassPathResource("application.yml")).forEach(sources::addLast);
        return Binder.get(env)
                .bind("northline.auth.rate-limits", RateLimitProperties.class)
                .get();
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
