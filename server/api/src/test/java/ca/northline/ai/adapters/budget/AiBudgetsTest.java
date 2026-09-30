package ca.northline.ai.adapters.budget;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.application.AiBudgets;
import ca.northline.ai.application.AiProperties;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/** Both budget stores behave the same: a per-minute rate per person, daily tokens per person and per business. */
class AiBudgetsTest {

    static final AiProperties.Budget LIMITS = new AiProperties.Budget(1_000, 5_000, 3);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T15:00:30Z"), ZoneOffset.UTC);

    abstract static class Contract {
        abstract AiBudgets budgets();

        @Test
        void thePerMinuteRateStopsTheFourthRequest() {
            var caller = Caller.person(Ids.next());
            for (int i = 0; i < 3; i++) {
                budgets().admit(caller);
            }
            assertThatThrownBy(() -> budgets().admit(caller))
                    .isInstanceOfSatisfying(
                            AiRateLimited.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.getLimit())
                                    .isEqualTo("person_rate"));
        }

        @Test
        void aPersonsDailyTokensRunOut() {
            var caller = Caller.person(Ids.next());
            budgets().charge(caller, 1_000);
            assertThatThrownBy(() -> budgets().admit(caller)).isInstanceOfSatisfying(AiRateLimited.class, e -> {
                org.assertj.core.api.Assertions.assertThat(e.getLimit()).isEqualTo("person_tokens");
                // 15:00 UTC = 09:00 in Edmonton: 15 h to midnight
                org.assertj.core.api.Assertions.assertThat(e.getRetryAfter().toHours())
                        .isEqualTo(14);
            });
        }

        @Test
        void aBusinessesTeamSharesItsDailyTokens() {
            var merchant = Ids.next();
            for (int i = 0; i < 5; i++) {
                budgets().charge(Caller.member(Ids.next(), merchant), 999);
            }
            assertThatCode(() -> budgets().admit(Caller.member(Ids.next(), merchant)))
                    .doesNotThrowAnyException();
            budgets().charge(Caller.member(Ids.next(), merchant), 5);
            assertThatThrownBy(() -> budgets().admit(Caller.member(Ids.next(), merchant)))
                    .isInstanceOfSatisfying(
                            AiRateLimited.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.getLimit())
                                    .isEqualTo("merchant_tokens"));
        }
    }

    @Nested
    class InMemory extends Contract {
        private final InMemoryAiBudgets budgets = new InMemoryAiBudgets(LIMITS, CLOCK);

        @Override
        AiBudgets budgets() {
            return budgets;
        }
    }

    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    static LettuceConnectionFactory factory;

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.destroy();
        }
        VALKEY.stop();
    }

    @Nested
    class Valkey extends Contract {
        private final ValkeyAiBudgets budgets;

        Valkey() {
            synchronized (AiBudgetsTest.class) {
                if (factory == null) {
                    VALKEY.start();
                    factory = new LettuceConnectionFactory(
                            new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379)));
                    factory.afterPropertiesSet();
                    factory.start();
                }
            }
            budgets = new ValkeyAiBudgets(new StringRedisTemplate(factory), LIMITS, CLOCK);
        }

        @Override
        AiBudgets budgets() {
            return budgets;
        }
    }
}
