package ca.northline.search.application;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** S-132 eval ({@code ai-eval/search-filters.json}): typed text → search API filters, nothing invented. */
public final class SearchFiltersEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("search-filters");

    @Override
    public String name() {
        return "search-filters";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var interpreter = new SearchInterpreter(kit.completions, new PromptLibraryAccess());
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            try {
                var r = interpreter.interpret(
                        "eval-visitor",
                        c.path("input").asString(),
                        c.path("lang").asString("en"),
                        c.path("hasLocation").asBoolean(false));
                model = r.model();
                var misses = new ArrayList<String>();
                if (r.q() == null
                        || !r.q().toLowerCase(java.util.Locale.ROOT)
                                .contains(c.path("qHas").asString())) {
                    misses.add("q=" + r.q());
                }
                c.path("expect").properties().forEach(e -> {
                    var actual = value(r, e.getKey());
                    if (!matches(e.getValue(), actual)) {
                        misses.add(e.getKey() + "=" + actual);
                    }
                });
                c.path("absent").forEach(a -> {
                    var actual = value(r, a.asString());
                    if (actual != null && !(actual instanceof List<?> l && l.isEmpty())) {
                        misses.add(a.asString() + " set to " + actual);
                    }
                });
                cases.add(new EvalReport.Case(
                        c.path("id").asString(),
                        "ok",
                        misses.isEmpty() ? "ok" : "bad",
                        misses.isEmpty(),
                        misses.isEmpty() ? r.explanation() : misses.toString(),
                        0,
                        0));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), "ok", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }

    static Object value(InterpretSearch.Interpretation r, String key) {
        return switch (key) {
            case "kind" -> r.kind();
            case "tier" -> r.tier();
            case "dietary" -> r.dietary();
            case "allergenFree" -> r.allergenFree();
            case "minPrice" -> r.minPrice();
            case "maxPrice" -> r.maxPrice();
            case "minRating" -> r.minRating();
            case "instantBook" -> r.instantBook();
            case "openNow" -> r.openNow();
            case "delivery" -> r.delivery();
            case "radiusKm" -> r.radiusKm();
            case "sort" -> r.sort();
            default -> throw new IllegalArgumentException(key);
        };
    }

    static boolean matches(JsonNode expected, Object actual) {
        if (expected.isArray()) {
            var want = new HashSet<String>();
            expected.forEach(v -> want.add(v.asString()));
            return actual instanceof List<?> l && new HashSet<>(l).equals(want);
        }
        if (expected.isBoolean()) {
            return Objects.equals(actual, expected.asBoolean());
        }
        if (expected.isNumber()) {
            return actual instanceof Number n && Math.abs(n.doubleValue() - expected.asDouble()) < 0.001;
        }
        return Objects.equals(actual, expected.asString());
    }
}
