package ca.northline.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.ai.MockOpenRouter;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.OperationsFixtures.Line;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * S-130 end to end: the Studio assistant with OpenRouter pointed at the local stand-in, tools running against the real
 * modules as the caller (membership, role, {@code acr=mfa}), writes proposed and confirmed, insights, streaming.
 */
class StudioAssistantApiTest extends IntegrationTest {

    static final MockOpenRouter MODEL = new MockOpenRouter();

    @DynamicPropertySource
    static void openRouter(DynamicPropertyRegistry registry) {
        registry.add("northline.ai.provider", () -> "openrouter");
        registry.add("northline.ai.openrouter.base-url", MODEL::baseUrl);
        registry.add("northline.ai.openrouter.api-key", () -> "sk-or-v1-FAKE-studio-test");
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        MODEL.reset();
    }

    record Seller(String merchantId, String owner, String order, String ref) {}

    Seller seller() {
        var fx = new OperationsFixtures(jdbc);
        var seller = data.merchant("seller", "Prairie Wrench Parts");
        var owner = data.user("Ravi");
        data.member(seller, owner, MerchantRole.OWNER);
        var other = data.merchant("seller", "Other shop");
        var order = fx.order(
                fx.window(Duration.ofHours(3), "R-611"),
                data.user("Amara Osei"),
                "placed",
                new Line(seller, "Wiper blades", 2, 1995, "pending"),
                new Line(other, "Floor mats", 1, 5000, "pending"));
        var ref = jdbc.sql("select ref from orders.orders where id = ?")
                .param(order)
                .query(String.class)
                .single();
        return new Seller(seller, owner, order, ref);
    }

    static String ask(String question) {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}],\"screen\":\"dashboard\"}";
    }

    List<String> offeredTools(int request) {
        return MODEL.requests.get(request).path("tools").findValuesAsString("name");
    }

    @Test
    void answersFromATheCallersOwnOrdersOnly() throws Exception {
        var s = seller();
        MODEL.enqueue(MockOpenRouter.toolCall("list_orders", "{\"status\":\"to_pack\"}"));
        MODEL.enqueue(MockOpenRouter.answer("One order to pack: " + s.ref() + "."));

        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("What do I have to pack?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("One order to pack: " + s.ref() + "."))
                .andExpect(jsonPath("$.toolRuns[0].tool").value("list_orders"))
                .andExpect(jsonPath("$.toolRuns[0].ok").value(true))
                .andExpect(jsonPath("$.screen").value("orders"))
                .andExpect(jsonPath("$.pending").value(nullValue()))
                .andExpect(jsonPath("$.usage.modelCalls").value(2));

        var system = MODEL.requests.getFirst().path("messages").path(0).path("content").asString();
        assertThat(system).contains("Prairie Wrench Parts").contains("owner").contains("see earnings");
        assertThat(system).doesNotContain("Ravi");
        JsonNode toolResult = MODEL.lastRequest().path("messages").path(3);
        var result = toolResult.path("content").asString();
        assertThat(result).contains(s.ref()).contains("Wiper blades").contains("3990");
        assertThat(result).as("no other merchant's lines, no customer name")
                .doesNotContain("Floor mats")
                .doesNotContain("Osei");
        assertThat(jdbc.sql("select count(*) from ai.usage where merchant_id = ? and feature = 'assistant'")
                        .param(s.merchantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void eachRoleIsOfferedOnlyItsTools() throws Exception {
        var s = seller();
        var bookkeeper = data.user("Priya");
        data.member(s.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        var technician = data.user("Jas");
        data.member(s.merchantId(), technician, MerchantRole.TECHNICIAN);
        MODEL.responder = _ -> MockOpenRouter.answer("ok");

        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(bookkeeper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("hi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value(nullValue()));
        assertThat(offeredTools(0)).contains("earnings_overview", "list_orders").doesNotContain("pack_order");

        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(technician))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("hi")))
                .andExpect(status().isOk());
        assertThat(offeredTools(1)).contains("pack_order", "list_orders").doesNotContain("earnings_overview", "payouts_overview");
    }

    @Test
    void aWriteIsOnlyProposedAndRunsWhenThePersonConfirms() throws Exception {
        var s = seller();
        MODEL.enqueue(MockOpenRouter.toolCall("pack_order", "{\"ref\":\"" + s.ref() + "\"}"));

        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("Mark it packed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.tool").value("pack_order"))
                .andExpect(jsonPath("$.pending.arguments.ref").value(s.ref()))
                .andExpect(jsonPath("$.pending.preview").value("Mark order " + s.ref() + " as packed"));
        assertThat(lineState(s)).as("nothing ran yet").isEqualTo("pending");

        mvc.perform(post("/api/v1/merchants/{m}/assistant/actions", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"pack_order\",\"arguments\":{\"ref\":\"" + s.ref() + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool").value("pack_order"))
                .andExpect(jsonPath("$.screen").value("orders"));
        assertThat(lineState(s)).isEqualTo("packed");
        assertThat(jdbc.sql("select count(*) from developer.audit_log where merchant_id = ? and action = 'assistant.action_confirmed'")
                        .param(s.merchantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void confirmingChecksTheRoleAndOnlyRunsWrites() throws Exception {
        var s = seller();
        var bookkeeper = data.user("Priya");
        data.member(s.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        mvc.perform(post("/api/v1/merchants/{m}/assistant/actions", s.merchantId())
                        .with(TestJwt.member(bookkeeper))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"pack_order\",\"arguments\":{\"ref\":\"" + s.ref() + "\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(post("/api/v1/merchants/{m}/assistant/actions", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"list_orders\"}"))
                .andExpect(status().isNotFound());
        assertThat(lineState(s)).isEqualTo("pending");
    }

    @Test
    void anotherBusinessesMemberAndASingleFactorSessionAreRefused() throws Exception {
        var s = seller();
        var stranger = data.business(MerchantRole.OWNER);
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(stranger.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("orders?")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.memberWithoutMfa(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("orders?")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        assertThat(MODEL.requests).isEmpty();
    }

    @Test
    void validationMessages() throws Exception {
        var s = seller();
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("messages"))
                .andExpect(jsonPath("$.errors[0].message").value("Type a question."));
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("x".repeat(4001))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Keep a message under 4,000 characters."));
    }

    @Test
    void streamsToolRunsTextAndTheAnswer() throws Exception {
        var s = seller();
        MODEL.enqueue(MockOpenRouter.toolCall("list_orders", "{}"));
        MODEL.enqueue(MockOpenRouter.answer("One order to pack."));
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat/stream", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(ask("What do I pack?")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event: tool\ndata: {\"tool\":\"list_orders\"")))
                .andExpect(content().string(containsString("event: delta\ndata: {\"text\":")))
                .andExpect(content().string(containsString("event: done\ndata: {\"content\":\"One order to pack.\"")));
        assertThat(MODEL.requests).allSatisfy(r -> assertThat(r.path("stream").asBoolean()).isTrue());
    }

    @Test
    void aProviderFailureBeforeTheFirstFrameIsAPlain503() throws Exception {
        var s = seller();
        MODEL.enqueue(MockOpenRouter.status(500));
        mvc.perform(post("/api/v1/merchants/{m}/assistant/chat/stream", s.merchantId())
                        .with(TestJwt.member(s.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ask("What do I pack?")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ai_unavailable"));
    }

    @Test
    void insightsReadTheScreensDataAsTheCaller() throws Exception {
        var s = seller();
        MODEL.enqueue(MockOpenRouter.answer(
                "{\"title\":\"1 order to pack\",\"body\":\"" + s.ref() + " is still to pack.\",\"bullets\":[\"Pack " + s.ref()
                        + "\"]}"));
        mvc.perform(get("/api/v1/merchants/{m}/assistant/insights/dashboard", s.merchantId())
                        .with(TestJwt.member(s.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("1 order to pack"))
                .andExpect(jsonPath("$.bullets[0]").value("Pack " + s.ref()));
        var sent = MODEL.lastRequest();
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(sent.path("model").asString()).isEqualTo("google/gemini-3.5-flash-lite");
        assertThat(sent.path("messages").path(1).path("content").asString())
                .contains("list_orders")
                .contains(s.ref())
                .doesNotContain("list_jobs");

        var technician = data.user("Jas");
        data.member(s.merchantId(), technician, MerchantRole.TECHNICIAN);
        mvc.perform(get("/api/v1/merchants/{m}/assistant/insights/earnings", s.merchantId())
                        .with(TestJwt.member(technician)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get("/api/v1/merchants/{m}/assistant/insights/weather", s.merchantId())
                        .with(TestJwt.member(s.owner())))
                .andExpect(status().isNotFound());
    }

    String lineState(Seller s) {
        return jdbc.sql("select state from orders.order_lines where order_id = ? and merchant_id = ?")
                .params(s.order(), s.merchantId())
                .query(String.class)
                .single();
    }
}
