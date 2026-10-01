package ca.northline.trust.application;

import static org.mockito.Mockito.mock;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;

/** S-131 eval ({@code ai-eval/review-summary.json}): reviews → bilingual summary and themes, nothing invented. */
public final class ReviewSummaryEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("review-summary");

    @Override
    public String name() {
        return "review-summary";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var service = new ReviewSummaryService(
                kit.completions, new PromptLibraryAccess(), mock(BrowseReviews.class), JsonMapper.builder().build());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var reviews = new ArrayList<DraftReviewSummary.ReviewText>();
            c.path("reviews").forEach(r -> reviews.add(new DraftReviewSummary.ReviewText(r.get(0).asInt(), r.get(1).asString(), r.get(2).asString())));
            try {
                var s = service.summarize("eval-merchant", "eval-owner", reviews);
                model = s.model();
                var en = s.en().summary() + "\n" + String.join("\n", s.en().themes());
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, en));
                if (s.fr().summary().isBlank()) {
                    misses.add("no French summary");
                }
                cases.add(new EvalReport.Case(
                        c.path("id").asString(), "ok", misses.isEmpty() ? "ok" : "bad", misses.isEmpty(),
                        misses.isEmpty() ? s.en().summary() : misses.toString(), 0, 0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), "ok", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
