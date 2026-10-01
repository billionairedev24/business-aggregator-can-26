package ca.northline.ai.adapters.fake;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.LlmClientContract;
import ca.northline.ai.api.LlmClient;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest extends LlmClientContract {

    private final FakeLlmClient client = new FakeLlmClient(JSON, "0", 2.0);

    @Override
    protected LlmClient client() {
        return client;
    }

    @Test
    void quotesTheQuestionAndReportsACost() {
        var res = client.complete(question("How many orders?"));
        assertThat(res.path("choices").path(0).path("message").path("content").asString())
                .isEqualTo("(fake model) You asked: How many orders?");
        assertThat(res.path("usage").path("cost").asDouble()).isPositive();
    }

    @Test
    void aJsonRequestGetsTheSystemPromptsExampleBack() {
        var body = JSON.createObjectNode();
        body.putObject("response_format").put("type", "json_object");
        var messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "Reply only with JSON like:\n```json\n{\"titleEn\": \"Brake inspection\"}\n```\n");
        messages.addObject().put("role", "user").put("content", "Draft it");
        var content = client.complete(body)
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asString();
        assertThat(JSON.readTree(content).path("titleEn").asString()).isEqualTo("Brake inspection");
    }

    @Test
    void readsLatencyRanges() {
        assertThat(FakeLlmClient.latencyRange("0")).containsExactly(0, 0);
        assertThat(FakeLlmClient.latencyRange("300-1800")).containsExactly(300, 1800);
        assertThat(FakeLlmClient.latencyRange("oops")).containsExactly(0, 0);
    }
}
