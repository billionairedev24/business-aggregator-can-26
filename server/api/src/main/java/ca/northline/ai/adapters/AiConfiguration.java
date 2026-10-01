package ca.northline.ai.adapters;

import ca.northline.ai.adapters.budget.InMemoryAiBudgets;
import ca.northline.ai.adapters.budget.ValkeyAiBudgets;
import ca.northline.ai.adapters.fake.FakeLlmClient;
import ca.northline.ai.adapters.openrouter.OpenRouterClient;
import ca.northline.ai.api.LlmClient;
import ca.northline.ai.application.AiBudgets;
import ca.northline.ai.application.AiProperties;
import ca.northline.ai.application.ObservedLlmClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Picks the {@link LlmClient} adapter by {@code northline.ai.provider} ({@code AI_PROVIDER}) and wraps it with redaction,
 * metrics and traces ({@link ObservedLlmClient}) — the only {@link LlmClient} bean. Staging and prod refuse {@code fake},
 * as they refuse the other stand-ins. Budgets live in Valkey outside local/test.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    LlmClient llmClient(
            AiProperties props,
            JsonMapper json,
            Environment environment,
            ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<MeterRegistry> meters) {
        LlmClient adapter = switch (props.provider()) {
            case FAKE -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException(
                            "AI_PROVIDER=fake (canned answers) is refused under staging and prod:"
                                    + " set AI_PROVIDER=openrouter and OPENROUTER_API_KEY (docs/runbooks/ai.md).");
                }
                log.info("AI: fake model (AI_PROVIDER=fake) — deterministic answers, nothing leaves the process.");
                yield new FakeLlmClient(
                        json, props.fake().latencyMs(), props.fake().usdPerMillionTokens());
            }
            case OPENROUTER -> {
                var client = new OpenRouterClient(props.openrouter(), json);
                if (client.configured()) {
                    log.info(
                            "AI: OpenRouter at {} (models {} / light {}, data_collection={}, zdr={}).",
                            props.openrouter().baseUrl(),
                            props.openrouter().model(),
                            props.openrouter().lightModel(),
                            props.openrouter().dataCollection(),
                            props.openrouter().zdr());
                } else {
                    log.warn("AI: AI_PROVIDER=openrouter without OPENROUTER_API_KEY — every AI feature answers 503.");
                }
                yield client;
            }
        };
        return new ObservedLlmClient(
                adapter,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP),
                meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }

    @Bean
    @Profile("local | test")
    AiBudgets aiBudgetsInMemory(AiProperties props, Clock clock) {
        return new InMemoryAiBudgets(props.budget(), clock);
    }

    @Bean
    @Profile("!local & !test")
    AiBudgets aiBudgetsInValkey(StringRedisTemplate redis, AiProperties props, Clock clock) {
        return new ValkeyAiBudgets(redis, props.budget(), clock);
    }
}
