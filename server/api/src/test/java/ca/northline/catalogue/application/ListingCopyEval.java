package ca.northline.catalogue.application;

import static org.mockito.Mockito.mock;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import ca.northline.catalogue.domain.ListingKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/** S-131 eval ({@code ai-eval/listing-copy.json}): listing facts → bilingual drafts, no invented claims. */
public final class ListingCopyEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("listing-copy");

    @Override
    public String name() {
        return "listing-copy";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var service = new ListingCopyService(
                kit.completions, new PromptLibraryAccess(), mock(CategoryCatalog.class), JsonMapper.builder().build());
        var frWords = new ArrayList<String>();
        set.root().path("frWords").forEach(w -> frWords.add(w.asString()));
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var f = c.path("facts");
            var attributes = new LinkedHashMap<String, String>();
            f.path("attributes").properties().forEach(e -> attributes.put(e.getKey(), e.getValue().asString()));
            try {
                var draft = service.draft(
                        "eval-merchant",
                        "eval-owner",
                        new DraftListingCopy.Facts(
                                ListingKind.valueOf(f.path("kind").asString().toUpperCase(Locale.ROOT)),
                                f.path("name").asString(null),
                                null,
                                f.path("brand").asString(null),
                                attributes,
                                f.path("included").asString(null),
                                f.path("durationMin").isNumber() ? f.path("durationMin").asInt() : null,
                                f.path("notes").asString(null)));
                model = draft.model();
                var en = draft.en().title() + "\n" + draft.en().description() + "\n" + String.join("\n", draft.en().bullets());
                var fr = draft.fr().title() + "\n" + draft.fr().description() + "\n" + String.join("\n", draft.fr().bullets());
                var misses = new ArrayList<>(LabelledSet.contentMisses(c, en + "\n" + fr));
                if (draft.en().title().isBlank() || draft.en().description().isBlank()) {
                    misses.add("no English draft");
                }
                var lowerFr = " " + LabelledSet.normalise(fr.replace('\n', ' ')) + " ";
                if (draft.fr().title().isBlank() || frWords.stream().noneMatch(lowerFr::contains)) {
                    misses.add("no French draft");
                }
                cases.add(new EvalReport.Case(
                        c.path("id").asString(), "ok", misses.isEmpty() ? "ok" : "bad", misses.isEmpty(),
                        misses.isEmpty() ? draft.en().title() + " / " + draft.fr().title() : misses.toString(), 0, 0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), "ok", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
