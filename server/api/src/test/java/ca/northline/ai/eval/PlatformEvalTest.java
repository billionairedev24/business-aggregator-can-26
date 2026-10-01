package ca.northline.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.MockOpenRouter;
import ca.northline.ai.adapters.openrouter.OpenRouterClient;
import ca.northline.ai.application.AiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlatformEvalTest {

    @Test
    void theLabelledSetPassesAgainstTheSimulatedModel() {
        var report = SimulatedEvals.run(new PlatformEval());
        assertThat(report.cases()).hasSize(10);
        assertThat(report.failures()).isEmpty();
        assertThat(report.precision("list_orders")).isEqualTo(1.0);
        assertThat(report.recall("none")).isEqualTo(1.0);
        assertThat(report.cost()).isPositive();
    }

    @Test
    void theGraderFailsAModelThatAnswersWithoutToolsOrLeaks() {
        try (var stub = new MockOpenRouter()) {
            stub.responder = _ -> MockOpenRouter.answer("NL-48190 shipped; I think you earned about $900.");
            var props = AiTestKit.properties(AiProperties.Provider.OPENROUTER, 4, stub.baseUrl());
            var kit = new AiTestKit(
                    new OpenRouterClient(
                            props.openrouter().withKey("sk-or-v1-FAKE-eval"),
                            JsonMapper.builder().build()),
                    AiProperties.Provider.OPENROUTER,
                    4);
            var report = new PlatformEval().run(kit, "broken");
            assertThat(report.passRate()).isLessThan(0.2);
            assertThat(report.cases().stream()
                            .filter(c -> c.id().equals("orders-refs"))
                            .findFirst()
                            .orElseThrow()
                            .detail())
                    .contains("LEAK NL-48190");
        }
    }

    @Test
    void templatesReadToolResults() {
        var json = JsonMapper.builder().build();
        var filled = SimulatedModel.fill(
                "{{t0.orders[1].ref}} / {{t0.missing}} / {{t1.x}}",
                java.util.List.of(json.readTree("{\"orders\":[{\"ref\":\"A\"},{\"ref\":\"B\"}]}")));
        assertThat(filled).isEqualTo("B / ? / ?");
    }
}
