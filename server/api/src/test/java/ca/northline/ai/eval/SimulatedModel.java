package ca.northline.ai.eval;

import ca.northline.ai.MockOpenRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The stand-in for a live model in CI ({@link MockOpenRouter#responder}): for each case of a {@link LabelledSet} it
 * replays the case's {@code mock} — {@code {"calls": [["tool", {args}]], "answer": "… {{t0.orders[0].ref}} …"}} (tool
 * calls, then an answer templated from what the tools really returned), {@code {"json": {…}}} or {@code {"text": "…"}}.
 * The case is the one whose {@code input} the last user message contains. It proves the plumbing (tools as the caller,
 * refusals, parsing, grading), not the model's judgement, which only the live eval measures.
 */
public final class SimulatedModel implements Function<JsonNode, String> {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{t(\\d+)\\.([^}]+)}}");

    private final List<JsonNode> cases;

    public SimulatedModel(LabelledSet set) {
        this.cases = set.cases();
    }

    @Override
    public String apply(JsonNode req) {
        var messages = req.path("messages");
        var lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).path("role").asString(""))) {
                lastUser = i;
            }
        }
        var question =
                lastUser < 0 ? "" : messages.get(lastUser).path("content").asString("");
        JsonNode match = null;
        for (var c : cases) {
            var input = c.path("input");
            var key = input.isString() ? input.asString() : c.path("id").asString();
            if (!key.isEmpty() && question.contains(key)) {
                match = c;
                break;
            }
        }
        if (match == null) {
            return MockOpenRouter.answer("(simulated model: no case matches)");
        }
        var mock = match.path("mock");
        if (mock.has("json")) {
            return MockOpenRouter.answer(LabelledSet.JSON.writeValueAsString(mock.get("json")));
        }
        var results = new ArrayList<JsonNode>();
        for (int i = lastUser + 1; i < messages.size(); i++) {
            var m = messages.get(i);
            if ("tool".equals(m.path("role").asString(""))) {
                results.add(LabelledSet.JSON.readTree(m.path("content").asString("{}")));
            }
        }
        var calls = mock.path("calls");
        if (results.isEmpty() && calls.isArray() && !calls.isEmpty() && req.has("tools")) {
            var out = new ArrayList<String[]>();
            for (int i = 0; i < calls.size(); i++) {
                out.add(new String[] {
                    "call_" + i,
                    calls.get(i).get(0).asString(),
                    LabelledSet.JSON.writeValueAsString(calls.get(i).get(1))
                });
            }
            return MockOpenRouter.toolCalls(out);
        }
        return MockOpenRouter.answer(
                fill(mock.path("answer").asString(mock.path("text").asString("")), results));
    }

    /** {@code {{t0.a.b[2].c}}}: field path into the n-th tool result; a missing value renders as "?". */
    static String fill(String template, List<JsonNode> results) {
        Matcher m = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        while (m.find()) {
            var index = Integer.parseInt(m.group(1));
            JsonNode node = index < results.size() ? results.get(index) : null;
            for (var part : m.group(2).split("\\.")) {
                if (node == null) {
                    break;
                }
                var bracket = part.indexOf('[');
                node = node.get(bracket < 0 ? part : part.substring(0, bracket));
                if (node != null && bracket >= 0) {
                    node = node.get(Integer.parseInt(part.substring(bracket + 1, part.length() - 1)));
                }
            }
            var value = node == null || node.isNull() ? "?" : node.isString() ? node.asString() : node.toString();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
