package ca.northline.messaging.application;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;

/** S-132 eval ({@code ai-eval/help-triage.json}): report → category; precision/recall per category, safety urgent. */
public final class HelpTriageEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("help-triage");

    @Override
    public String name() {
        return "help-triage";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var triage = new HelpTriage(kit.completions, new PromptLibraryAccess());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var expected = c.path("expected").asString();
            try {
                var t = triage.triage("eval-customer", c.path("input").asString(), null);
                model = t.model();
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, t.summary()));
                if (c.path("urgentExpected").asBoolean(false) && !t.urgent()) {
                    misses.add("not urgent");
                }
                if (t.summary().isBlank()) {
                    misses.add("no summary");
                }
                var actual = t.category().code();
                cases.add(new EvalReport.Case(
                        c.path("id").asString(),
                        expected,
                        actual,
                        expected.equals(actual) && misses.isEmpty(),
                        misses.isEmpty() ? t.summary() : misses.toString(),
                        0,
                        0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), expected, "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
