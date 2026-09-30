package ca.northline.ai.adapters.openrouter;

import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.LlmClient;
import ca.northline.ai.application.AiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code northline.ai.provider=openrouter}: the {@link LlmClient} port over OpenRouter's OpenAI-compatible
 * {@code POST /chat/completions} (docs/runbooks/ai.md). Adds the model, the attribution headers ({@code HTTP-Referer},
 * {@code X-Title}), usage accounting ({@code usage.include}: tokens and OpenRouter's own cost come back with the answer,
 * in the last chunk when streaming) and the data policy ({@code provider.data_collection}, {@code provider.zdr}: only
 * endpoints that neither train on nor keep prompts). Streams are OpenAI-style server-sent events, tool-call fragments
 * joined by index.
 *
 * <p>A thin RestClient adapter rather than Spring AI's OpenAI model: see DECISIONS.md S-129 (OpenRouter's cost and
 * provider-routing fields, the tool loop and streaming stay ours, behind the same port). openrouter.ai has never been
 * called from this repository's builds: {@code OpenRouterClientTest} runs against a local stand-in, and
 * {@code AiEvalLiveTest} is the opt-in check against the real service.
 */
@Slf4j
public final class OpenRouterClient implements LlmClient {

    private final AiProperties.OpenRouter props;
    private final JsonMapper json;
    private final RestClient http;

    public OpenRouterClient(AiProperties.OpenRouter props, JsonMapper json) {
        this.props = props;
        this.json = json;
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder()
                .requestFactory(requests)
                .baseUrl(props.baseUrl())
                .build();
    }

    @Override
    public String name() {
        return "openrouter";
    }

    @Override
    public boolean configured() {
        return !props.apiKey().isBlank();
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public JsonNode complete(ObjectNode request) {
        return send(prepare(request, false), res -> json.readTree(res.getBody()));
    }

    @Override
    public JsonNode stream(ObjectNode request, Consumer<String> onDelta) {
        return send(prepare(request, true), res -> assemble(res, onDelta));
    }

    private ObjectNode prepare(ObjectNode body, boolean stream) {
        if (!configured()) {
            throw new AiUnavailable("AI is not configured here (OPENROUTER_API_KEY is not set).");
        }
        if (!body.path("model").isString()) {
            body.put("model", props.model());
        }
        body.put("stream", stream);
        body.putObject("usage").put("include", true);
        var provider = body.putObject("provider");
        provider.put("data_collection", props.dataCollection());
        if (props.zdr()) {
            provider.put("zdr", true);
        }
        return body;
    }

    private interface Reader {
        JsonNode read(ClientHttpResponse response) throws IOException;
    }

    private JsonNode send(ObjectNode body, Reader reader) {
        var stream = body.path("stream").asBoolean(false);
        try {
            return http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.apiKey())
                    .header("HTTP-Referer", props.referer())
                    .header("X-Title", props.title())
                    .body(json.writeValueAsString(body))
                    .exchange((_, res) -> {
                        check(res.getStatusCode(), res);
                        return reader.read(res);
                    });
        } catch (ResourceAccessException e) {
            var timedOut = e.getMessage() != null
                    && e.getMessage().toLowerCase(Locale.ROOT).contains("timed out");
            log.warn("OpenRouter unreachable: {}", e.getMessage());
            throw new AiUnavailable(timedOut ? "The model timed out." : "The model could not be reached.", e);
        } catch (UncheckedIOException e) {
            log.warn("OpenRouter answer cut off: {}", e.getMessage());
            throw new AiUnavailable("The model's answer was cut off.", e);
        }
    }

    private void check(HttpStatusCode status, ClientHttpResponse res) throws IOException {
        if (status.is2xxSuccessful()) {
            return;
        }
        var text = new String(res.getBody().readNBytes(600), StandardCharsets.UTF_8);
        if (status.value() == 429) {
            var after = res.getHeaders().getFirst("Retry-After");
            throw new AiRateLimited(
                    "provider", Duration.ofSeconds(seconds(after)), "The model is busy; try again shortly.");
        }
        log.warn("OpenRouter answered {}: {}", status.value(), text);
        throw new AiUnavailable("The model answered with an error (" + status.value() + ").");
    }

    private static long seconds(@Nullable String header) {
        try {
            return header == null ? 10 : Math.clamp(Long.parseLong(header.strip()), 1, 300);
        } catch (NumberFormatException _) {
            return 10;
        }
    }

    /**
     * Reads {@code data: {chunk}} … {@code data: [DONE]} (comment lines such as {@code : OPENROUTER PROCESSING} and blank
     * separators skipped), hands each piece of text to {@code onDelta} and returns the chat-completions shape.
     */
    private JsonNode assemble(ClientHttpResponse res, Consumer<String> onDelta) throws IOException {
        var content = new StringBuilder();
        var calls = new TreeMap<Integer, ObjectNode>();
        String model = null;
        String finish = null;
        JsonNode usage = null;
        try (var lines = new BufferedReader(new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                var data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = json.readTree(data);
                } catch (JacksonException _) {
                    continue;
                }
                if (chunk.has("error")) {
                    log.warn("OpenRouter stream error: {}", chunk.get("error"));
                    throw new AiUnavailable("The model failed mid-answer.");
                }
                if (chunk.path("model").isString()) {
                    model = chunk.get("model").asString();
                }
                if (chunk.path("usage").isObject()) {
                    usage = chunk.get("usage");
                }
                var choice = chunk.path("choices").path(0);
                if (choice.path("finish_reason").isString()) {
                    finish = choice.get("finish_reason").asString();
                }
                var delta = choice.path("delta");
                if (delta.path("content").isString()
                        && !delta.get("content").asString().isEmpty()) {
                    var piece = delta.get("content").asString();
                    content.append(piece);
                    onDelta.accept(piece);
                }
                for (var fragment : delta.path("tool_calls")) {
                    var index = fragment.path("index").asInt(calls.size());
                    var call = calls.computeIfAbsent(index, _ -> {
                        var c = json.createObjectNode().put("type", "function");
                        c.putObject("function").put("name", "").put("arguments", "");
                        return c;
                    });
                    if (fragment.path("id").isString()) {
                        call.put("id", fragment.get("id").asString());
                    }
                    var fn = fragment.path("function");
                    var target = (ObjectNode) call.get("function");
                    if (fn.path("name").isString()) {
                        target.put(
                                "name",
                                target.get("name").asString() + fn.get("name").asString());
                    }
                    if (fn.path("arguments").isString()) {
                        target.put(
                                "arguments",
                                target.get("arguments").asString()
                                        + fn.get("arguments").asString());
                    }
                }
            }
        }
        var out = json.createObjectNode();
        if (model != null) {
            out.put("model", model);
        }
        if (usage != null) {
            out.set("usage", usage);
        }
        var choice = out.putArray("choices").addObject();
        choice.put("finish_reason", finish == null ? "stop" : finish);
        var message = choice.putObject("message").put("role", "assistant").put("content", content.toString());
        if (!calls.isEmpty()) {
            var arr = message.putArray("tool_calls");
            calls.values().forEach(arr::add);
        }
        return out;
    }
}
