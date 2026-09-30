package ca.northline.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.MockOpenRouter;
import ca.northline.ai.adapters.openrouter.OpenRouterClient;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Message;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiCompletions.StreamSink;
import ca.northline.ai.api.AiCompletions.ToolContext;
import ca.northline.ai.api.AiCompletions.ToolRun;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.AssistantTool;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.MerchantPermission;
import ca.northline.shared.security.MerchantRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The platform's request path against the mock OpenRouter: tool loop, permissions, writes, metering, redaction. */
class DefaultAiCompletionsTest {

    static final MockOpenRouter STUB = new MockOpenRouter();
    static final String MERCHANT = "01J9ZD3V00000000000000PWM1";
    static final String USER = "01J9ZD3V00000000000000RAV1";

    AiTestKit kit;
    final AtomicInteger ordersRuns = new AtomicInteger();

    /** A read tool for operators. */
    final AssistantTool orders = new TestTool("list_orders", MerchantPermission.OPERATE, false) {
        @Override
        public Result run(Call call) {
            ordersRuns.incrementAndGet();
            return new Result(
                    Map.of(
                            "orders",
                            List.of(Map.of("ref", "NL-48213", "state", "to_pack", "note", "call 403-555-0199"))),
                    "orders to pack → 1");
        }
    };

    /** A finance read tool (bookkeepers and owners). */
    final AssistantTool earnings = new TestTool("earnings_summary", MerchantPermission.FINANCE_READ, false) {
        @Override
        public Result run(Call call) {
            throw new NotFound("payout", "P-1");
        }
    };

    /** A write: never run by the loop. */
    final AssistantTool pack = new TestTool("mark_packed", MerchantPermission.OPERATE, true) {
        @Override
        public Result run(Call call) {
            throw new AssertionError("a write must not run without confirmation");
        }

        @Override
        public String preview(Call call) {
            return "Mark " + call.text("ref") + " as packed";
        }
    };

    @BeforeEach
    void setUp() {
        STUB.reset();
        kit = new AiTestKit(
                new OpenRouterClient(
                        AiTestKit.properties(AiProperties.Provider.OPENROUTER, 3, STUB.baseUrl())
                                .openrouter()
                                .withKey("sk-or-v1-FAKE-test"),
                        tools.jackson.databind.json.JsonMapper.builder().build()),
                AiProperties.Provider.OPENROUTER,
                3);
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    Request question(String text) {
        return new Request(
                AiFeature.ASSISTANT,
                Caller.member(USER, MERCHANT),
                "studio-assistant@v1",
                List.of(Message.system("You are the Studio assistant."), Message.user(text)),
                false,
                500);
    }

    ToolContext as(MerchantRole role) {
        return new ToolContext(MERCHANT, USER, role, Locale.CANADA);
    }

    @Test
    void aToolRunsAsTheCallerAndItsResultGoesBackRedacted() {
        STUB.enqueue(MockOpenRouter.toolCall("list_orders", "{\"state\":\"to_pack\"}"));
        STUB.enqueue(MockOpenRouter.answer("One order to pack: NL-48213."));
        var answer = kit.completions.converse(
                question("What do I pack?"), List.of(orders, earnings, pack), as(MerchantRole.OWNER), null);

        assertThat(answer.text()).isEqualTo("One order to pack: NL-48213.");
        assertThat(answer.toolRuns()).containsExactly(new ToolRun("list_orders", "orders to pack → 1", true));
        assertThat(answer.screen()).isEqualTo("orders");
        assertThat(answer.usage().modelCalls()).isEqualTo(2);
        assertThat(answer.usage().costUsd()).isEqualTo(0.0016);
        verify(kit.access).require(MERCHANT, MerchantPermission.OPERATE);

        var first = STUB.requests.getFirst();
        assertThat(first.path("model").asString()).isEqualTo("google/gemini-3.7-flash");
        assertThat(first.path("tools").findValuesAsString("name"))
                .containsExactlyInAnyOrder("list_orders", "earnings_summary", "mark_packed");
        var toolMessage = STUB.lastRequest().path("messages").path(3);
        assertThat(toolMessage.path("role").asString()).isEqualTo("tool");
        assertThat(toolMessage.path("content").asString()).contains("NL-48213").contains("[phone]");
        assertThat(toolMessage.path("content").asString()).doesNotContain("403-555-0199");

        assertThat(kit.usage).singleElement().satisfies(u -> {
            assertThat(u.feature()).isEqualTo("assistant");
            assertThat(u.merchantId()).isEqualTo(MERCHANT);
            assertThat(u.prompt()).isEqualTo("studio-assistant@v1");
            assertThat(u.costMicroUsd()).isEqualTo(1600);
            assertThat(u.toolRuns()).isEqualTo(1);
            assertThat(u.outcome()).isEqualTo("ok");
        });
        assertThat(kit.meters
                        .get("northline.ai.cost")
                        .tag("feature", "assistant")
                        .counter()
                        .count())
                .isEqualTo(0.0016, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(kit.meters
                        .get("northline.ai.tokens")
                        .tag("kind", "prompt")
                        .counter()
                        .count())
                .isEqualTo(1700);
    }

    @Test
    void aRoleOnlyGetsTheToolsItMayUseAndARefusalReachesTheModel() {
        STUB.enqueue(MockOpenRouter.toolCall("list_orders", "{}"));
        STUB.enqueue(MockOpenRouter.answer("Your role can't see orders."));
        var answer = kit.completions.converse(
                question("Orders?"), List.of(orders, earnings), as(MerchantRole.BOOKKEEPER), null);
        assertThat(STUB.requests.getFirst().path("tools").findValuesAsString("name"))
                .containsExactly("earnings_summary");
        assertThat(answer.toolRuns()).containsExactly(new ToolRun("list_orders", "list_orders: unknown_tool", false));
        assertThat(ordersRuns).hasValue(0);
    }

    @Test
    void membershipIsCheckedAgainOnEveryRun() {
        doThrow(new MerchantAccessDenied(
                        MerchantAccessDenied.Reason.NOT_A_MEMBER, "You are not a member of this business."))
                .when(kit.access)
                .require(eq(MERCHANT), eq(MerchantPermission.OPERATE));
        STUB.enqueue(MockOpenRouter.toolCall("list_orders", "{}"));
        STUB.enqueue(MockOpenRouter.answer("I can't see that."));
        var answer = kit.completions.converse(question("Orders?"), List.of(orders), as(MerchantRole.OWNER), null);
        assertThat(answer.toolRuns()).containsExactly(new ToolRun("list_orders", "list_orders: forbidden", false));
        assertThat(STUB.lastRequest().path("messages").path(3).path("content").asString())
                .contains("forbidden")
                .contains("not a member");
        assertThat(ordersRuns).hasValue(0);
    }

    @Test
    void aDomainErrorIsReadByTheModel() {
        STUB.enqueue(MockOpenRouter.toolCall("earnings_summary", "{}"));
        STUB.enqueue(MockOpenRouter.answer("No payout found."));
        var answer = kit.completions.converse(question("Payout?"), List.of(earnings), as(MerchantRole.OWNER), null);
        assertThat(answer.toolRuns().getFirst().summary()).isEqualTo("earnings_summary: not_found");
    }

    @Test
    void aWriteStopsTheLoopForConfirmation() {
        STUB.enqueue(MockOpenRouter.toolCall("mark_packed", "{\"ref\":\"NL-48213\"}"));
        var answer = kit.completions.converse(question("Pack it"), List.of(pack), as(MerchantRole.OWNER), null);
        assertThat(answer.pending()).isNotNull();
        assertThat(answer.pending().tool()).isEqualTo("mark_packed");
        assertThat(answer.pending().arguments()).containsEntry("ref", "NL-48213");
        assertThat(answer.pending().preview()).isEqualTo("Mark NL-48213 as packed");
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void toolRoundsAreCappedAndTheLastRoundOffersNoTools() {
        for (int i = 0; i < 3; i++) {
            STUB.enqueue(MockOpenRouter.toolCall("list_orders", "{}"));
        }
        STUB.enqueue(MockOpenRouter.answer("Done."));
        var answer = kit.completions.converse(question("Loop"), List.of(orders), as(MerchantRole.OWNER), null);
        assertThat(answer.text()).isEqualTo("Done.");
        assertThat(STUB.requests).hasSize(4);
        assertThat(STUB.lastRequest().has("tools")).isFalse();
        assertThat(ordersRuns).hasValue(3);
    }

    @Test
    void streamingReportsToolRunsAndText() {
        STUB.enqueue(MockOpenRouter.toolCall("list_orders", "{}"));
        STUB.enqueue(MockOpenRouter.answer("One order to pack."));
        var events = new ArrayList<String>();
        var answer =
                kit.completions.converse(question("Pack?"), List.of(orders), as(MerchantRole.OWNER), new StreamSink() {
                    @Override
                    public void tool(ToolRun run) {
                        events.add("tool:" + run.tool());
                    }

                    @Override
                    public void delta(String text) {
                        events.add("delta:" + text);
                    }
                });
        assertThat(events.getFirst()).isEqualTo("tool:list_orders");
        assertThat(String.join(
                        "",
                        events.stream()
                                .filter(e -> e.startsWith("delta:"))
                                .map(e -> e.substring(6))
                                .toList()))
                .isEqualTo("One order to pack.");
        assertThat(answer.text()).isEqualTo("One order to pack.");
        assertThat(STUB.requests)
                .allSatisfy(r -> assertThat(r.path("stream").asBoolean()).isTrue());
    }

    @Test
    void aJsonRequestAsksForJsonAndUsesTheLightModelForLightFeatures() {
        STUB.enqueue(MockOpenRouter.answer("```json\n{\"category\": \"refund\"}\n```"));
        var answer = kit.completions.complete(new Request(
                        AiFeature.HELP_TRIAGE,
                        Caller.person(USER),
                        "help-triage@v1",
                        List.of(Message.system("Classify."), Message.user("My order never came; email me at a@b.co")),
                        false,
                        200)
                .asJson());
        assertThat(answer.json())
                .hasValueSatisfying(
                        j -> assertThat(j.path("category").asString()).isEqualTo("refund"));
        var sent = STUB.lastRequest();
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(sent.path("model").asString()).isEqualTo("google/gemini-3.5-flash-lite");
        assertThat(sent.path("messages").path(1).path("content").asString()).endsWith("email me at [email]");
    }

    @Test
    void budgetsAreChargedAndEnforced() {
        var tight = new AiTestKit(
                new OpenRouterClient(
                        AiTestKit.properties(AiProperties.Provider.OPENROUTER, 3, STUB.baseUrl())
                                .openrouter()
                                .withKey("sk-or-v1-FAKE-test"),
                        tools.jackson.databind.json.JsonMapper.builder().build()),
                AiProperties.Provider.OPENROUTER,
                3);
        var budgets = new ca.northline.ai.adapters.budget.InMemoryAiBudgets(
                new AiProperties.Budget(1_000, 1_000_000, 20), java.time.Clock.systemUTC());
        var completions = new DefaultAiCompletions(
                tight.client, budgets, tight.usage::add, tight.access, tight.props, tight.json);
        STUB.responder = _ -> MockOpenRouter.answer("ok");
        completions.complete(question("one")); // 960 tokens
        completions.complete(question("two")); // now 1,920 ≥ 1,000
        assertThatThrownBy(() -> completions.complete(question("three")))
                .isInstanceOfSatisfying(
                        AiRateLimited.class, e -> assertThat(e.getLimit()).isEqualTo("person_tokens"));
        assertThat(tight.usage).extracting(AiUsageLog.Entry::outcome).containsExactly("ok", "ok", "rate_limited");
        assertThat(STUB.requests).hasSize(2);
    }

    @Test
    void withoutAKeyEveryCallIs503AndNothingIsSent() {
        var off = new AiTestKit(
                new OpenRouterClient(
                        AiTestKit.properties(AiProperties.Provider.OPENROUTER, 3, STUB.baseUrl())
                                .openrouter()
                                .withKey(""),
                        tools.jackson.databind.json.JsonMapper.builder().build()),
                AiProperties.Provider.OPENROUTER,
                3);
        assertThat(off.completions.available()).isFalse();
        assertThatThrownBy(() -> off.completions.complete(question("x"))).isInstanceOf(AiUnavailable.class);
        assertThat(STUB.requests).isEmpty();
    }

    @Test
    void providerFailuresAreRecordedAndMetered() {
        STUB.enqueue(MockOpenRouter.status(500));
        assertThatThrownBy(() -> kit.completions.complete(question("x"))).isInstanceOf(AiUnavailable.class);
        assertThat(kit.usage).extracting(AiUsageLog.Entry::outcome).containsExactly("unavailable");
        assertThat(kit.meters
                        .get("northline.ai.completion")
                        .tag("outcome", "unavailable")
                        .timer()
                        .count())
                .isEqualTo(1);
        verify(kit.access, org.mockito.Mockito.never()).require(anyString(), org.mockito.ArgumentMatchers.any());
    }

    /** A tool with fixed metadata. */
    abstract static class TestTool implements AssistantTool {
        private final String name;
        private final MerchantPermission permission;
        private final boolean write;

        TestTool(String name, MerchantPermission permission, boolean write) {
            this.name = name;
            this.permission = permission;
            this.write = write;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Test tool " + name;
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("state", Map.of("type", "string"), "ref", Map.of("type", "string"));
        }

        @Override
        public MerchantPermission permission() {
            return permission;
        }

        @Override
        public boolean write() {
            return write;
        }

        @Override
        public String screen() {
            return "orders";
        }
    }
}
