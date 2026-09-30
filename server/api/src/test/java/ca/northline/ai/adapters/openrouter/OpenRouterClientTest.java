package ca.northline.ai.adapters.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.ai.LlmClientContract;
import ca.northline.ai.MockOpenRouter;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.LlmClient;
import ca.northline.ai.application.AiProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenRouter adapter against a local stand-in of {@code /api/v1/chat/completions} (openrouter.ai is unreachable here). */
class OpenRouterClientTest extends LlmClientContract {

    static final MockOpenRouter STUB = new MockOpenRouter();

    static AiProperties.OpenRouter props(String key) {
        return new AiProperties.OpenRouter(
                STUB.baseUrl(),
                key,
                "google/gemini-3.7-flash",
                "google/gemini-3.5-flash-lite",
                "http://localhost:3100",
                "Northline",
                "deny",
                true,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private final OpenRouterClient client = new OpenRouterClient(props("sk-or-v1-FAKE-test-key"), JSON);

    @BeforeEach
    void reset() {
        STUB.reset();
        STUB.responder = _ -> MockOpenRouter.answer("Hello there");
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Override
    protected LlmClient client() {
        return client;
    }

    @Test
    void theRequestCarriesTheKeyAttributionUsageAccountingAndDataPolicy() {
        client.complete(question("Say hello"));
        var headers = STUB.headers.getLast();
        assertThat(headers.get("authorization")).isEqualTo("Bearer sk-or-v1-FAKE-test-key");
        assertThat(headers.get("http-referer")).isEqualTo("http://localhost:3100");
        assertThat(headers.get("x-title")).isEqualTo("Northline");
        var body = STUB.lastRequest();
        assertThat(body.path("model").asString()).isEqualTo("google/gemini-3.7-flash");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("usage").path("include").asBoolean()).isTrue();
        assertThat(body.path("provider").path("data_collection").asString()).isEqualTo("deny");
        assertThat(body.path("provider").path("zdr").asBoolean()).isTrue();
    }

    @Test
    void aModelNamedByTheRequestWins() {
        var q = question("x");
        q.put("model", "google/gemini-3.5-flash-lite");
        client.complete(q);
        assertThat(STUB.lastRequest().path("model").asString()).isEqualTo("google/gemini-3.5-flash-lite");
    }

    @Test
    void aStreamJoinsToolCallFragmentsAndKeepsTheUsage() {
        STUB.enqueue(MockOpenRouter.toolCalls(List.<String[]>of(
                new String[] {"c1", "list_orders", "{\"state\":\"to_pack\"}"},
                new String[] {"c2", "earnings_summary", "{}"})));
        var deltas = new ArrayList<String>();
        var res = client.stream(question("What should I do today?"), deltas::add);
        assertThat(STUB.headers.getLast().get("accept")).isEqualTo("text/event-stream");
        var calls = res.path("choices").path(0).path("message").path("tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.path(0).path("function").path("name").asString()).isEqualTo("list_orders");
        assertThat(calls.path(0).path("function").path("arguments").asString()).isEqualTo("{\"state\":\"to_pack\"}");
        assertThat(calls.path(1).path("id").asString()).isEqualTo("c2");
        assertThat(res.path("usage").path("cost").asDouble()).isEqualTo(0.0007);
        assertThat(res.path("choices").path(0).path("finish_reason").asString()).isEqualTo("tool_calls");
    }

    @Test
    void providerErrorsBecomeStableProblems() {
        STUB.enqueue(MockOpenRouter.status(429));
        assertThatThrownBy(() -> client.complete(question("x"))).isInstanceOfSatisfying(AiRateLimited.class, e -> {
            assertThat(e.getLimit()).isEqualTo("provider");
            assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(7));
        });
        STUB.enqueue(MockOpenRouter.status(502));
        assertThatThrownBy(() -> client.complete(question("x"))).isInstanceOf(AiUnavailable.class);
        STUB.enqueue(MockOpenRouter.status(402));
        assertThatThrownBy(() -> client.stream(question("x"), _ -> {})).isInstanceOf(AiUnavailable.class);
        STUB.enqueue(MockOpenRouter.STREAM_ERROR);
        assertThatThrownBy(() -> client.stream(question("x"), _ -> {}))
                .isInstanceOf(AiUnavailable.class)
                .hasMessageContaining("mid-answer");
    }

    @Test
    void anUnreachableProviderIsUnavailable() {
        var down = new OpenRouterClient(
                new AiProperties.OpenRouter(
                        "http://127.0.0.1:9/api/v1",
                        "sk-or-v1-FAKE",
                        "m",
                        "l",
                        "r",
                        "t",
                        "deny",
                        true,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1)),
                JSON);
        assertThatThrownBy(() -> down.complete(question("x"))).isInstanceOf(AiUnavailable.class);
    }

    @Test
    void withoutAKeyNothingIsSent() {
        var unconfigured = new OpenRouterClient(props(""), JSON);
        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.complete(question("x")))
                .isInstanceOf(AiUnavailable.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
        assertThat(STUB.requests).isEmpty();
    }
}
