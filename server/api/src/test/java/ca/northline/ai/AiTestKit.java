package ca.northline.ai;

import static org.mockito.Mockito.mock;

import ca.northline.ai.adapters.budget.InMemoryAiBudgets;
import ca.northline.ai.api.LlmClient;
import ca.northline.ai.application.AiBudgets;
import ca.northline.ai.application.AiProperties;
import ca.northline.ai.application.AiUsageLog;
import ca.northline.ai.application.DefaultAiCompletions;
import ca.northline.ai.application.ObservedLlmClient;
import ca.northline.shared.security.MerchantAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the AI platform without Spring for unit tests and evals: the given adapter behind the observed port, generous
 * in-memory budgets, a recording usage log and a {@link MerchantAccess} mock that allows everything (tests that check
 * refusals stub it).
 */
public final class AiTestKit {

    public final JsonMapper json = JsonMapper.builder().build();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final List<AiUsageLog.Entry> usage = new CopyOnWriteArrayList<>();
    public final MerchantAccess access = mock(MerchantAccess.class);
    public final LlmClient client;
    public final AiProperties props;
    public final AiBudgets budgets;
    public final DefaultAiCompletions completions;

    public AiTestKit(LlmClient adapter, AiProperties.Provider provider, int maxToolRounds) {
        this.client = new ObservedLlmClient(adapter, ObservationRegistry.NOOP, meters);
        this.props = properties(provider, maxToolRounds, "");
        this.budgets =
                new InMemoryAiBudgets(new AiProperties.Budget(10_000_000, 10_000_000, 10_000), Clock.systemUTC());
        this.completions = new DefaultAiCompletions(client, budgets, usage::add, access, props, json);
    }

    /** The fake model, as under local. */
    public static AiTestKit fake() {
        return new AiTestKit(
                new ca.northline.ai.adapters.fake.FakeLlmClient(
                        JsonMapper.builder().build(), "0", 0),
                AiProperties.Provider.FAKE,
                4);
    }

    public static AiProperties properties(AiProperties.Provider provider, int maxToolRounds, String baseUrl) {
        return new AiProperties(
                provider,
                maxToolRounds,
                new AiProperties.Budget(200_000, 1_000_000, 20),
                new AiProperties.Fake("0", 0),
                new AiProperties.OpenRouter(
                        baseUrl.isEmpty() ? "https://openrouter.ai/api/v1" : baseUrl,
                        System.getenv().getOrDefault("OPENROUTER_API_KEY", ""),
                        System.getenv().getOrDefault("OPENROUTER_MODEL", "google/gemini-3.7-flash"),
                        System.getenv().getOrDefault("OPENROUTER_LIGHT_MODEL", "google/gemini-3.5-flash-lite"),
                        "http://localhost:3100",
                        "Northline (eval)",
                        "deny",
                        !"false".equals(System.getenv("OPENROUTER_ZDR")),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(90)),
                Map.of());
    }
}
