package ca.northline.trust.application;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;

/**
 * S-133 eval ({@code ai-eval/trust-screen.json}): listings, reviews and messages → flag | ok. Precision and recall of
 * "flag"; a flag passes only with the expected category and an explanation that leaks nothing listed.
 */
public final class TrustScreenEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("trust-screen");

    @Override
    public String name() {
        return "trust-screen";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var screener = new TrustScreener(kit.completions, new PromptLibraryAccess());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var id = c.path("id").asString();
            var expected = c.path("expected").asString();
            try {
                var hints = new ArrayList<String>();
                c.path("hints").forEach(h -> hints.add(h.asString()));
                var v = screener.screen(new TrustScreener.Item(
                        c.path("kind").asString(),
                        c.path("context").asString(),
                        hints,
                        c.path("input").asString()));
                model = v.model();
                var actual = v.flag() ? "flag" : "ok";
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, v.explanation()));
                if (v.flag() && v.explanation().isBlank()) {
                    misses.add("flag without an explanation");
                }
                var category = c.path("category").asString("");
                if (v.flag()
                        && "flag".equals(expected)
                        && !category.isEmpty()
                        && !v.categories().contains(category)) {
                    misses.add("categories " + v.categories() + " lack " + category);
                }
                cases.add(new EvalReport.Case(
                        id,
                        expected,
                        actual,
                        expected.equals(actual) && misses.isEmpty(),
                        misses.isEmpty() ? v.categories() + " " + v.explanation() : misses.toString(),
                        0,
                        0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(id, expected, "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
