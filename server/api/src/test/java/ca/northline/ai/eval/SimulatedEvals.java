package ca.northline.ai.eval;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.MockOpenRouter;
import ca.northline.ai.adapters.openrouter.OpenRouterClient;
import ca.northline.ai.application.AiProperties;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Runs a suite against its simulated model, through the real OpenRouter adapter and the mock OpenRouter. */
public final class SimulatedEvals {
    private SimulatedEvals() {}

    public static EvalReport run(EvalSuite suite) {
        try (var stub = new MockOpenRouter()) {
            stub.responder = new SimulatedModel(suite.set());
            var props = AiTestKit.properties(AiProperties.Provider.OPENROUTER, 4, stub.baseUrl());
            var kit = new AiTestKit(
                    new OpenRouterClient(
                            props.openrouter().withKey("sk-or-v1-FAKE-eval"),
                            JsonMapper.builder().build()),
                    AiProperties.Provider.OPENROUTER,
                    4);
            var report = suite.run(kit, "simulated");
            report.write(Path.of("build/ai-eval"));
            return report;
        }
    }
}
