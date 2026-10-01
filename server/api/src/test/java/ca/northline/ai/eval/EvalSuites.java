package ca.northline.ai.eval;

import java.util.List;

/** Every eval suite, run against the simulated model by each suite's test and against OpenRouter by the live eval. */
public final class EvalSuites {
    private EvalSuites() {}

    public static List<EvalSuite> all() {
        return List.of(
                new PlatformEval(),
                new ca.northline.studio.application.AssistantEval(),
                new ca.northline.catalogue.application.ListingCopyEval(),
                new ca.northline.booking.application.QuoteLinesEval(),
                new ca.northline.messaging.application.ReplyEval(),
                new ca.northline.trust.application.ReviewSummaryEval(),
                new ca.northline.search.application.SearchFiltersEval(),
                new ca.northline.messaging.application.HelpTriageEval());
    }
}
