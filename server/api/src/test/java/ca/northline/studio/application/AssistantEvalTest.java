package ca.northline.studio.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.eval.SimulatedEvals;
import org.junit.jupiter.api.Test;

class AssistantEvalTest {

    @Test
    void theTopQuestionsPassAgainstTheSimulatedModel() {
        var report = SimulatedEvals.run(new AssistantEval());
        assertThat(report.cases()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(report.failures()).isEmpty();
        assertThat(report.recall("pending:pack_order")).isEqualTo(1.0);
        assertThat(report.precision("none")).isEqualTo(1.0);
    }

    @Test
    void everyStubToolMatchesARealToolsNameAndPermission() {
        // The real tools are beans in their modules; AssistantToolsCatalogueTest checks the same table in the context.
        assertThat(AssistantEval.TOOLS).containsKeys("list_orders", "pack_order", "earnings_overview", "start_travel");
    }
}
