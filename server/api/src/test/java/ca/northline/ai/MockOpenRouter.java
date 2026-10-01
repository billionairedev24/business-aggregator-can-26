package ca.northline.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A local stand-in for {@code https://openrouter.ai/api/v1/chat/completions} (openrouter.ai is not reachable from the
 * build). Tests queue canned answers or install a {@link #responder} (the evals' simulated model); every request body
 * and header set is recorded. A request with {@code "stream": true} gets the same answer as OpenRouter's server-sent
 * events: a processing comment, content and tool-call fragments, a last chunk with {@code finish_reason} and
 * {@code usage}, then {@code data: [DONE]}. One instance per test class.
 */
public final class MockOpenRouter implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String MODEL = "google/gemini-3.7-flash";

    private final HttpServer server;
    private final Deque<String> queued = new ArrayDeque<>();
    public final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    public final List<Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());

    /** When set and nothing is queued, answers each request from its body. */
    public volatile @Nullable Function<JsonNode, String> responder;

    public MockOpenRouter() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/v1/chat/completions", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public void reset() {
        synchronized (queued) {
            queued.clear();
        }
        requests.clear();
        headers.clear();
        responder = null;
    }

    public void enqueue(String reply) {
        synchronized (queued) {
            queued.addLast(reply);
        }
    }

    public JsonNode lastRequest() {
        return requests.getLast();
    }

    private void handle(HttpExchange ex) throws IOException {
        var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var req = JSON.readTree(body);
        requests.add(req);
        var h = new HashMap<String, String>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        headers.add(h);
        String reply;
        synchronized (queued) {
            reply = queued.pollFirst();
        }
        var r = responder;
        if (reply == null) {
            reply = r != null ? r.apply(req) : answer("(no scripted reply)");
        }
        if (reply.startsWith("HTTP ")) {
            var status = Integer.parseInt(reply.substring(5, 8));
            if (status == 429) {
                ex.getResponseHeaders().add("Retry-After", "7");
            }
            send(
                    ex,
                    status,
                    "application/json",
                    "{\"error\":{\"message\":\"scripted failure\",\"code\":" + status + "}}");
            return;
        }
        if (req.path("stream").asBoolean(false)) {
            send(ex, 200, "text/event-stream", sse(reply));
            return;
        }
        send(ex, 200, "application/json", reply);
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        var out = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, out.length);
        try (var os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** Splits a canned completion into stream chunks the way OpenRouter sends them. */
    static String sse(String reply) {
        if (reply.equals(STREAM_ERROR)) {
            return ": OPENROUTER PROCESSING\n\ndata: {\"error\":{\"message\":\"provider overloaded\",\"code\":502}}\n\n";
        }
        var full = JSON.readTree(reply);
        var model = full.path("model").asString(MODEL);
        var choice = full.path("choices").path(0);
        var msg = choice.path("message");
        var out = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        var content = msg.path("content").isString() ? msg.get("content").asString() : "";
        var step = Math.max(1, (content.length() + 2) / 3);
        for (int i = 0; i < content.length(); i += step) {
            var piece = content.substring(i, Math.min(content.length(), i + step));
            out.append(chunk(model, delta -> delta.put("content", piece)));
        }
        var index = 0;
        for (var call : msg.path("tool_calls")) {
            var idx = index++;
            var args = call.path("function").path("arguments").asString("");
            var half = args.length() / 2;
            out.append(chunk(model, delta -> {
                var c = delta.putArray("tool_calls").addObject();
                c.put("index", idx).put("id", call.path("id").asString()).put("type", "function");
                c.putObject("function")
                        .put("name", call.path("function").path("name").asString())
                        .put("arguments", args.substring(0, half));
            }));
            out.append(chunk(model, delta -> {
                var c = delta.putArray("tool_calls").addObject();
                c.put("index", idx);
                c.putObject("function").put("arguments", args.substring(half));
            }));
        }
        var last = JSON.createObjectNode().put("id", "gen-s").put("model", model);
        var lc = last.putArray("choices").addObject();
        lc.put("index", 0).put("finish_reason", choice.path("finish_reason").asString("stop"));
        lc.putObject("delta");
        if (full.has("usage")) {
            last.set("usage", full.get("usage"));
        }
        out.append("data: ").append(JSON.writeValueAsString(last)).append("\n\n");
        out.append("data: [DONE]\n\n");
        return out.toString();
    }

    private static String chunk(String model, Consumer<ObjectNode> fill) {
        var c = JSON.createObjectNode().put("id", "gen-s").put("model", model);
        var ch = c.putArray("choices").addObject().put("index", 0);
        fill.accept(ch.putObject("delta"));
        return "data: " + JSON.writeValueAsString(c) + "\n\n";
    }

    // ---- canned answers -------------------------------------------------------------------------------------------

    /** The next call fails with this HTTP status. */
    public static String status(int code) {
        return "HTTP " + code;
    }

    /** The next streamed call gets an error chunk instead of an answer. */
    public static final String STREAM_ERROR = "STREAM_ERROR";

    private static ObjectNode usage(ObjectNode out, int prompt, int completion, double cost) {
        out.putObject("usage")
                .put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", prompt + completion)
                .put("cost", cost);
        return out;
    }

    /** A final answer (900 + 60 tokens, $0.0009). */
    public static String answer(String content) {
        var out = JSON.createObjectNode().put("id", "gen-2").put("model", MODEL);
        var choice = out.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        return JSON.writeValueAsString(usage(out, 900, 60, 0.0009));
    }

    /** Tool calls: each {@code {id, name, argumentsJson}} (800 + 40 tokens, $0.0007). */
    public static String toolCalls(List<String[]> calls) {
        var out = JSON.createObjectNode().put("id", "gen-1").put("model", MODEL);
        var choice = out.putArray("choices").addObject().put("index", 0).put("finish_reason", "tool_calls");
        var message = choice.putObject("message").put("role", "assistant");
        message.putNull("content");
        ArrayNode arr = message.putArray("tool_calls");
        for (var c : calls) {
            var o = arr.addObject().put("id", c[0]).put("type", "function");
            o.putObject("function").put("name", c[1]).put("arguments", c[2]);
        }
        return JSON.writeValueAsString(usage(out, 800, 40, 0.0007));
    }

    public static String toolCall(String name, String argumentsJson) {
        return toolCalls(List.<String[]>of(new String[] {"call_0", name, argumentsJson}));
    }
}
