package ca.northline.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.api.LlmClient;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What every {@link LlmClient} adapter must do, whatever the provider. An adapter's test extends this with a configured
 * client whose provider answers any question (a local stand-in for an HTTP adapter). A new provider proves itself here.
 */
public abstract class LlmClientContract {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected abstract LlmClient client();

    protected static ObjectNode question(String text) {
        var body = JSON.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return body;
    }

    @Test
    void itNamesItsProviderAndModel() {
        assertThat(client().name()).matches("[a-z0-9-]+");
        assertThat(client().configured()).isTrue();
        assertThat(client().model()).isNotBlank();
    }

    @Test
    void aCompletionHasTheChatCompletionsShape() {
        var res = client().complete(question("Say hello"));
        var message = res.path("choices").path(0).path("message");
        assertThat(message.path("role").asString()).isEqualTo("assistant");
        assertThat(message.path("content").asString()).isNotBlank();
        assertThat(res.path("model").asString()).isNotBlank();
        assertThat(res.path("usage").isObject()).as("usage accounting").isTrue();
    }

    @Test
    void aStreamHandsOverTheTextAsItComesAndReturnsTheSameShape() {
        var deltas = new ArrayList<String>();
        var res = client().stream(question("Say hello"), deltas::add);
        var content =
                res.path("choices").path(0).path("message").path("content").asString();
        assertThat(deltas).isNotEmpty();
        assertThat(String.join("", deltas)).isEqualTo(content);
        assertThat(res.path("choices").path(0).path("finish_reason").asString()).isNotBlank();
    }
}
