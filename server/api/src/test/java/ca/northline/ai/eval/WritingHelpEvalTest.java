package ca.northline.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.booking.application.QuoteLinesEval;
import ca.northline.catalogue.application.ListingCopyEval;
import ca.northline.messaging.application.ReplyEval;
import ca.northline.trust.application.ReviewSummaryEval;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** S-131: every writing-help labelled set passes against the simulated model (the live run: AiEvalLiveTest). */
class WritingHelpEvalTest {

    static Stream<EvalSuite> suites() {
        return Stream.of(new ListingCopyEval(), new QuoteLinesEval(), new ReplyEval(), new ReviewSummaryEval());
    }

    @ParameterizedTest
    @MethodSource("suites")
    void passesAgainstTheSimulatedModel(EvalSuite suite) {
        var report = SimulatedEvals.run(suite);
        assertThat(report.cases()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(report.failures()).as(suite.name()).isEmpty();
    }
}
