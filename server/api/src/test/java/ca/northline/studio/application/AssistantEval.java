package ca.northline.studio.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.api.AssistantTool;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantPermission;
import ca.northline.shared.security.MerchantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-130 eval ({@code ai-eval/assistant.json}): the top questions, asked through the real {@link StudioAssistantService}
 * (prompt, role filtering, tool loop, write proposals) with stub tools that carry the real tools' names and permissions
 * and return the set's data. Graded on the first tool (or the proposed write, or none) and the answer's content.
 */
public final class AssistantEval implements EvalSuite {

    /** The real tools' names, permissions and whether they write (kept in step with the modules' AssistantTools). */
    static final Map<String, Object[]> TOOLS = Map.of(
            "list_orders",
                    new Object[] {
                        MerchantPermission.VIEW,
                        false,
                        "Shop orders with counts per status, items, totals and run cut-offs."
                    },
            "pack_order",
                    new Object[] {
                        MerchantPermission.OPERATE,
                        true,
                        "Propose marking one order (ref) as packed. The person must confirm."
                    },
            "list_jobs",
                    new Object[] {
                        MerchantPermission.VIEW,
                        false,
                        "Booked jobs from a date (from, days): ref, service, local times, state, who is assigned."
                    },
            "start_travel",
                    new Object[] {
                        MerchantPermission.OPERATE,
                        true,
                        "Propose starting travel to a job today (ref). The person must confirm."
                    },
            "list_listings",
                    new Object[] {
                        MerchantPermission.VIEW,
                        false,
                        "Listings: name, kind, price, stock, sales in 30 days, vetting state and flags, live or hidden."
                    },
            "get_availability",
                    new Object[] {
                        MerchantPermission.VIEW, false, "Weekly hours per team member, time off and holiday closures."
                    },
            "earnings_overview",
                    new Object[] {
                        MerchantPermission.FINANCE_READ,
                        false,
                        "Earnings: releasing with the next payout, available, escrow, on hold, next payout, ledger."
                    },
            "payouts_overview",
                    new Object[] {
                        MerchantPermission.FINANCE_READ,
                        false,
                        "Payouts: available, payable, next payout time, schedule, recent payouts."
                    },
            "list_threads",
                    new Object[] {
                        MerchantPermission.VIEW, false, "Message threads: ref, subject, kind, unread, last activity."
                    },
            "reviews_summary",
                    new Object[] {
                        MerchantPermission.VIEW,
                        false,
                        "Reviews: average, count, distribution, latest reviews (rating, job, text, replied)."
                    });

    private final LabelledSet set = LabelledSet.load("assistant");

    @Override
    public String name() {
        return "assistant";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    List<AssistantTool> tools() {
        var data = set.root().path("tools");
        var out = new ArrayList<AssistantTool>();
        TOOLS.forEach((name, meta) -> out.add(new AssistantTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return (String) meta[2];
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of(
                        "ref", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "from", Map.of("type", "string"),
                        "days", Map.of("type", "integer"));
            }

            @Override
            public MerchantPermission permission() {
                return (MerchantPermission) meta[0];
            }

            @Override
            public boolean write() {
                return (Boolean) meta[1];
            }

            @Override
            public String preview(Call call) {
                return name + " " + call.text("ref");
            }

            @Override
            public Result run(Call call) {
                return new Result(data.path(name), name + " → ok");
            }
        }));
        return out;
    }

    StudioAssistantService service(AiTestKit kit) {
        var business = set.root().path("business");
        var directory = mock(MerchantDirectory.class);
        when(directory.profile(any()))
                .thenReturn(Optional.of(new MerchantDirectory.MerchantProfile(
                        "eval-merchant",
                        business.path("type").asString(),
                        "master",
                        "active",
                        null,
                        "XX",
                        "Testville")));
        var names = mock(BusinessNames.class);
        when(names.displayName(any()))
                .thenReturn(Optional.of(business.path("name").asString()));
        var places = mock(MerchantPlaces.class);
        when(places.of(any()))
                .thenReturn(new MerchantPlaces.MerchantPlace(
                        "XX", true, "Testville", null, ZoneId.of("UTC-06:00"), "Test Province", "Province test", null));
        return new StudioAssistantService(
                kit.completions,
                new PromptLibraryAccess(),
                tools(),
                kit.access,
                directory,
                names,
                places,
                mock(AuditTrail.class),
                JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-10-01T18:00:00Z"), ZoneId.of("UTC")));
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var assistant = service(kit);
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var role = CodedEnums.fromCode(c.path("role").asString("owner"), MerchantRole.class);
            var member = new CurrentMember(
                    "eval-merchant", "eval-" + c.path("role").asString(), role == null ? MerchantRole.OWNER : role);
            var locale = Locale.forLanguageTag(c.path("locale").asString("en") + "-CA");
            var expected = c.path("expected").asString();
            try {
                var answer = assistant.ask(
                        new StudioAssistant.Question(
                                member,
                                List.of(new StudioAssistant.Turn(
                                        "user", c.path("input").asString())),
                                null,
                                locale),
                        null);
                model = answer.model();
                var actual = answer.pending() != null
                        ? "pending:" + answer.pending().tool()
                        : answer.toolRuns().isEmpty()
                                ? "none"
                                : answer.toolRuns().getFirst().tool();
                var misses = LabelledSet.contentMisses(c, answer.content());
                var toolOk = expected.equals(actual) || matchesAny(c.path("expectedAny"), actual);
                var tokens = answer.usage() == null ? 0 : answer.usage().totalTokens();
                cases.add(new EvalReport.Case(
                        c.path("id").asString(),
                        expected,
                        toolOk ? expected : actual,
                        toolOk && misses.isEmpty(),
                        misses.isEmpty() ? answer.content() : misses + " → " + answer.content(),
                        tokens,
                        answer.usage() == null || answer.usage().costUsd() == null
                                ? 0
                                : answer.usage().costUsd()));
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(c.path("id").asString(), expected, "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }

    private static boolean matchesAny(JsonNode alternatives, String actual) {
        for (var a : alternatives) {
            if (a.asString().equals(actual)) {
                return true;
            }
        }
        return false;
    }
}
