package ca.northline.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.trust.application.AnomalyScanEval;
import ca.northline.trust.application.TrustScreenEval;
import org.junit.jupiter.api.Test;

/**
 * S-133: the trust-screen and anomaly-scan labelled sets against the simulated model (live: AiEvalLiveTest).
 * Precision/recall of "flag" are measured; every flag carries an explanation (checked per case).
 */
class TrustSafetyEvalTest {

    @Test
    void screeningIsMeasuredWithPrecisionAndRecall() {
        var report = SimulatedEvals.run(new TrustScreenEval());
        assertThat(report.cases()).hasSize(18);
        assertThat(report.failures()).isEmpty();
        assertThat(report.precision("flag")).isEqualTo(1.0);
        assertThat(report.recall("flag")).isEqualTo(1.0);
        assertThat(report.cases().stream().filter(c -> c.expected().equals("ok")))
                .hasSize(7);
    }

    @Test
    void theAnomalyRulesPickTheLabelledBusinessesAndTheModelExplainsThem() {
        var report = SimulatedEvals.run(new AnomalyScanEval());
        assertThat(report.failures()).isEmpty();
        assertThat(report.precision("flag")).isEqualTo(1.0);
        assertThat(report.recall("flag")).isEqualTo(1.0);
        assertThat(report.cases()).hasSize(8);
    }
}
