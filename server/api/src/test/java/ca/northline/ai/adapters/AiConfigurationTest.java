package ca.northline.ai.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ca.northline.ai.api.LlmClient;
import ca.northline.ai.application.AiBudgets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Provider selection and the staging/prod guard, the same pattern as the other providers. */
class AiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    void theFakeIsTheLocalDefault() {
        runner.withPropertyValues("spring.profiles.active=local").run(ctx -> {
            assertThat(ctx.getBean(LlmClient.class).name()).isEqualTo("fake");
            assertThat(ctx.getBean(AiBudgets.class).getClass().getSimpleName()).isEqualTo("InMemoryAiBudgets");
        });
    }

    @Test
    void stagingAndProdRefuseTheFake() {
        for (var profile : new String[] {"staging", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "northline.ai.provider=fake")
                    .run(ctx -> assertThat(ctx)
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("AI_PROVIDER=fake")
                            .hasMessageContaining("refused"));
        }
    }

    @Test
    void openRouterWithoutAKeyStartsAndAnswers503AndBudgetsGoToValkey() {
        runner.withPropertyValues("spring.profiles.active=staging", "northline.ai.provider=openrouter")
                .run(ctx -> {
                    var client = ctx.getBean(LlmClient.class);
                    assertThat(client.name()).isEqualTo("openrouter");
                    assertThat(client.configured()).isFalse();
                    assertThat(client.model()).isEqualTo("google/gemini-3.7-flash");
                    assertThat(ctx.getBean(AiBudgets.class).getClass().getSimpleName())
                            .isEqualTo("ValkeyAiBudgets");
                });
    }
}
