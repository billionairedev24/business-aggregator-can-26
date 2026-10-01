package ca.northline.ai.adapters.fake;

import ca.northline.ai.api.LlmClient;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code northline.ai.provider=fake} (the default under local, test and dev; refused under staging/prod): a
 * deterministic model with no network and no key, so every AI screen works offline and the plumbing (budgets, usage,
 * streaming, the tool loop) runs end to end.
 *
 * <ul>
 *   <li>A JSON request ({@code response_format: json_object}) gets the first {@code ```json} example block of the
 *       system prompt back: every prompt that asks for JSON shows its shape, so the draft screens fill offline.
 *   <li>Otherwise it answers "(fake model) You asked: …" quoting the last user message. It never calls a tool.
 *   <li>{@code northline.ai.fake.latency-ms} ({@code 0}, or {@code min-max}) sleeps per call and
 *       {@code usd-per-million-tokens} reports a cost, so the local dashboards look like a real model's. Both off by
 *       default.
 * </ul>
 */
public final class FakeLlmClient implements LlmClient {

    public static final String MODEL = "fake/echo-1";

    private static final Pattern EXAMPLE = Pattern.compile("```json\\s*(\\{[\\s\\S]*?})\\s*```");

    private final JsonMapper json;
    private final int minLatencyMs;
    private final int maxLatencyMs;
    private final double usdPerMillionTokens;

    public FakeLlmClient(JsonMapper json, String latencyMs, double usdPerMillionTokens) {
        this.json = json;
        var range = latencyRange(latencyMs);
        this.minLatencyMs = range[0];
        this.maxLatencyMs = range[1];
        this.usdPerMillionTokens = Math.max(0, usdPerMillionTokens);
    }

    /** "0", "500" or "300-1800" → {min, max} ms; anything unreadable is no delay. */
    static int[] latencyRange(String spec) {
        try {
            var p = spec.strip().split("-", 2);
            var lo = Math.max(0, Integer.parseInt(p[0].strip()));
            var hi = p.length > 1 ? Math.max(lo, Integer.parseInt(p[1].strip())) : lo;
            return new int[] {Math.min(lo, 60_000), Math.min(hi, 60_000)};
        } catch (NumberFormatException _) {
            return new int[] {0, 0};
        }
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public JsonNode complete(ObjectNode request) {
        sleep();
        return answer(request, reply(request));
    }

    @Override
    public JsonNode stream(ObjectNode request, Consumer<String> onDelta) {
        sleep();
        var text = reply(request);
        for (var word : text.split("(?<= )")) {
            onDelta.accept(word);
        }
        return answer(request, text);
    }

    private String reply(ObjectNode request) {
        String system = "";
        String user = "";
        for (var m : request.path("messages")) {
            var role = m.path("role").asString("");
            if (role.equals("system") && system.isEmpty()) {
                system = m.path("content").asString("");
            } else if (role.equals("user")) {
                user = m.path("content").asString("");
            }
        }
        if (request.path("response_format").path("type").asString("").equals("json_object")) {
            var example = EXAMPLE.matcher(system);
            return example.find() ? example.group(1) : "{}";
        }
        return "(fake model) You asked: " + user;
    }

    private JsonNode answer(ObjectNode request, String text) {
        var out = json.createObjectNode();
        out.put("model", MODEL);
        var choice = out.putArray("choices").addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", text);
        var prompt = request.toString().length() / 4;
        var completion = Math.max(1, text.length() / 4);
        out.putObject("usage")
                .put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", prompt + completion)
                .put("cost", Math.round((prompt + completion) * usdPerMillionTokens) / 1_000_000d);
        return out;
    }

    private void sleep() {
        if (maxLatencyMs <= 0) {
            return;
        }
        var ms = minLatencyMs
                + (maxLatencyMs > minLatencyMs
                        ? ThreadLocalRandom.current().nextLong(maxLatencyMs - minLatencyMs + 1L)
                        : 0);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
