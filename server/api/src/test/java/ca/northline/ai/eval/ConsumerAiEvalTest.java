package ca.northline.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.messaging.application.HelpTriageEval;
import ca.northline.search.application.SearchFiltersEval;
import org.junit.jupiter.api.Test;

/** S-132: the search-filters and help-triage labelled sets against the simulated model (live: AiEvalLiveTest). */
class ConsumerAiEvalTest {

    @Test
    void searchQueriesMapToFilters() {
        var report = SimulatedEvals.run(new SearchFiltersEval());
        assertThat(report.cases()).hasSize(10);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void triageIsMeasuredPerCategoryAndSafetyIsNeverMissed() {
        var report = SimulatedEvals.run(new HelpTriageEval());
        assertThat(report.failures()).isEmpty();
        assertThat(report.recall("safety")).isEqualTo(1.0);
        assertThat(report.precision("safety")).isEqualTo(1.0);
        assertThat(report.recall("missing_item")).isEqualTo(1.0);
    }
}
