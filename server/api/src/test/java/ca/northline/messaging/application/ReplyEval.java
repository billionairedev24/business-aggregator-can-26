package ca.northline.messaging.application;

import static org.mockito.Mockito.mock;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/** S-131 eval ({@code ai-eval/message-reply.json}): thread → replies that never go off-platform or leak contact. */
public final class ReplyEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("message-reply");

    @Override
    public String name() {
        return "message-reply";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var service = new ReplySuggestions(
                kit.completions, new PromptLibraryAccess(), mock(BrowseInbox.class), JsonMapper.builder().build());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var turns = new ArrayList<SuggestReplies.Turn>();
            c.path("turns").forEach(t -> turns.add(new SuggestReplies.Turn(t.get(0).asString(), t.get(1).asString())));
            try {
                var s = service.suggest(
                        "eval-merchant",
                        "eval-owner",
                        c.path("refType").asString(),
                        turns,
                        Locale.forLanguageTag(c.path("locale").asString("en") + "-CA"));
                model = s.model();
                var all = String.join("\n", s.replies());
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, all));
                if (s.replies().size() < 2) {
                    misses.add("replies: " + s.replies().size());
                }
                cases.add(new EvalReport.Case(
                        c.path("id").asString(), "ok", misses.isEmpty() ? "ok" : "bad", misses.isEmpty(),
                        misses.isEmpty() ? s.replies().getFirst() : misses + " → " + all.replace('\n', '|'), 0, 0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), "ok", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
