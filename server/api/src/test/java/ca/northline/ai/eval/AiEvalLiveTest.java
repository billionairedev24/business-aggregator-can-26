package ca.northline.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.adapters.openrouter.OpenRouterClient;
import ca.northline.ai.application.AiProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every eval suite against the REAL OpenRouter, only when {@code OPENROUTER_API_KEY} is set (never in CI by default):
 *
 * <pre>
 * OPENROUTER_API_KEY=sk-or-v1-… [OPENROUTER_MODEL=… OPENROUTER_LIGHT_MODEL=…] [AI_EVAL_MIN_PASS=0.8] \
 *   ./gradlew :api:test --tests '*AiEvalLiveTest'
 * </pre>
 *
 * Each suite must meet its gate; reports land in {@code server/api/build/ai-eval/<suite>-live.{md,json}} with the
 * model, tokens and cost. Costs a few cents per run (docs/runbooks/ai.md § Live eval).
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class AiEvalLiveTest {

    @TestFactory
    Iterable<DynamicTest> everySuiteMeetsItsGateAgainstOpenRouter() {
        var base = System.getenv().getOrDefault("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1");
        var props = AiTestKit.properties(AiProperties.Provider.OPENROUTER, 4, base);
        var kit = new AiTestKit(
                new OpenRouterClient(props.openrouter(), JsonMapper.builder().build()),
                AiProperties.Provider.OPENROUTER,
                4);
        var gateOverride = System.getenv("AI_EVAL_MIN_PASS");
        return EvalSuites.all().stream()
                .map(suite -> DynamicTest.dynamicTest(suite.name(), () -> {
                    var report = suite.run(kit, "live");
                    report.write(Path.of("build/ai-eval"));
                    var gate = gateOverride == null ? suite.gate() : Double.parseDouble(gateOverride);
                    assertThat(report.passRate())
                            .as("%s pass rate (gate %s); failures: %s", suite.name(), gate, report.failures())
                            .isGreaterThanOrEqualTo(gate);
                }))
                .toList();
    }
}
