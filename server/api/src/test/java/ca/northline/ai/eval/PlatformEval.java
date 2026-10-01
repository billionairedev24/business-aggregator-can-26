package ca.northline.ai.eval;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Message;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiCompletions.ToolContext;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.AssistantTool;
import ca.northline.shared.security.MerchantPermission;
import ca.northline.shared.security.MerchantRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S-129 platform eval ({@code ai-eval/platform.json}): does the model pick the right tool for a plain question, answer
 * from its result, and decline what no tool covers? Five stub tools with fixed data stand in for the modules' tools,
 * so the set also measures a candidate model before any feature uses it.
 */
public final class PlatformEval implements EvalSuite {

    private final LabelledSet set = LabelledSet.load("platform");

    @Override
    public String name() {
        return "platform";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    static List<AssistantTool> tools() {
        return List.of(
                tool(
                        "list_orders",
                        "Orders of this business with their state (to_pack, packed, shipped).",
                        Map.of(
                                "orders",
                                List.of(
                                        Map.of("ref", "NL-48213", "state", "to_pack", "items", 2),
                                        Map.of("ref", "NL-48220", "state", "to_pack", "items", 1),
                                        Map.of("ref", "NL-48190", "state", "shipped", "items", 4)))),
                tool(
                        "list_bookings",
                        "Upcoming jobs (bookings) with start time and service.",
                        Map.of(
                                "bookings",
                                List.of(
                                        Map.of(
                                                "ref",
                                                "BK-7712",
                                                "service",
                                                "Brake inspection",
                                                "startsAt",
                                                "2026-10-02T09:00-06:00"),
                                        Map.of(
                                                "ref",
                                                "BK-7715",
                                                "service",
                                                "Oil & filter",
                                                "startsAt",
                                                "2026-10-02T13:30-06:00")))),
                tool(
                        "earnings_summary",
                        "Net earnings this month (CAD cents) and the next payout.",
                        Map.of(
                                "monthNetCents",
                                1_284_050,
                                "nextPayout",
                                Map.of("amountCents", 214_060, "on", "2026-10-03"))),
                tool(
                        "list_reviews",
                        "Recent verified reviews with rating and whether they have a reply.",
                        Map.of(
                                "average",
                                4.8,
                                "reviews",
                                List.of(
                                        Map.of("rating", 5, "replied", true, "job", "alternator"),
                                        Map.of("rating", 3, "replied", false, "job", "oil & filter")))),
                tool(
                        "list_messages",
                        "Customer threads waiting for a reply.",
                        Map.of(
                                "unanswered",
                                2,
                                "threads",
                                List.of(
                                        Map.of("ref", "BK-7712", "waitingMinutes", 45),
                                        Map.of("ref", "NL-48213", "waitingMinutes", 130)))));
    }

    private static AssistantTool tool(String name, String description, Object data) {
        return new AssistantTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of();
            }

            @Override
            public MerchantPermission permission() {
                return MerchantPermission.VIEW;
            }

            @Override
            public Result run(Call call) {
                return new Result(data, name + " → ok");
            }
        };
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var prompt = new ca.northline.ai.application.PromptLibraryAccess().get("platform-check");
        var cases = new ArrayList<EvalReport.Case>();
        String model = kit.client.model();
        for (var c : set.cases()) {
            var question = c.path("input").asString();
            var request = new Request(
                    AiFeature.PLATFORM,
                    Caller.member("eval-owner", "eval-merchant"),
                    prompt.id(),
                    List.of(Message.system(prompt.text()), Message.user(question)),
                    false,
                    400);
            try {
                var answer = kit.completions.converse(
                        request,
                        tools(),
                        new ToolContext("eval-merchant", "eval-owner", MerchantRole.OWNER, Locale.CANADA, java.time.ZoneOffset.UTC),
                        null);
                model = answer.model();
                var actual = answer.toolRuns().isEmpty()
                        ? "none"
                        : answer.toolRuns().getFirst().tool();
                var expected = c.path("expected").asString();
                var misses = LabelledSet.contentMisses(c, answer.text());
                cases.add(new EvalReport.Case(
                        c.path("id").asString(),
                        expected,
                        actual,
                        expected.equals(actual) && misses.isEmpty(),
                        misses.isEmpty() ? answer.text() : misses + " → " + answer.text(),
                        answer.usage().totalTokens(),
                        answer.usage().costUsd() == null ? 0 : answer.usage().costUsd()));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(
                        c.path("id").asString(), c.path("expected").asString(), "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
