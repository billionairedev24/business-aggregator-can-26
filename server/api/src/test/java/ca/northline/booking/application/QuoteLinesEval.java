package ca.northline.booking.application;

import static org.mockito.Mockito.mock;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/** S-131 eval ({@code ai-eval/quote-lines.json}): job request → line kinds and descriptions, never prices. */
public final class QuoteLinesEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("quote-lines");

    @Override
    public String name() {
        return "quote-lines";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var service = new QuoteLineSuggestions(
                kit.completions,
                new PromptLibraryAccess(),
                mock(QuoteRequests.class),
                JsonMapper.builder().build());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var j = c.path("job");
            try {
                var s = service.suggest(
                        "eval-merchant",
                        "eval-owner",
                        new DraftQuoteLines.Job(
                                j.path("title").asString(),
                                j.path("description").asString(null),
                                j.path("area").asString(null)),
                        null,
                        Locale.forLanguageTag(c.path("locale").asString("en") + "-CA"));
                model = s.model();
                var text = String.join(
                        "\n",
                        s.lines().stream()
                                .map(DraftQuoteLines.Line::description)
                                .toList());
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, text));
                if (s.lines().isEmpty() || s.lines().size() > 6) {
                    misses.add("lines: " + s.lines().size());
                }
                var kinds = s.lines().stream().map(l -> l.kind().code()).toList();
                c.path("kinds").forEach(k -> {
                    if (!kinds.contains(k.asString())) {
                        misses.add("no " + k.asString() + " line");
                    }
                });
                cases.add(new EvalReport.Case(
                        c.path("id").asString(),
                        "ok",
                        misses.isEmpty() ? "ok" : "bad",
                        misses.isEmpty(),
                        misses.isEmpty() ? text.replace('\n', ';') : misses.toString(),
                        0,
                        0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), "ok", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
