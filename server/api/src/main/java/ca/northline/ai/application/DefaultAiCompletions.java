package ca.northline.ai.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.AssistantTool;
import ca.northline.ai.api.LlmClient;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** {@link AiCompletions}: budgets, the feature's model, the bounded tool loop and the usage record. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAiCompletions implements AiCompletions {

    /** A tool result longer than this is cut (it goes back to the model on every later round). */
    static final int MAX_TOOL_RESULT = 12_000;

    static final int MAX_ANSWER = 8_000;

    private final LlmClient client;
    private final AiBudgets budgets;
    private final AiUsageLog usageLog;
    private final MerchantAccess access;
    private final AiProperties props;
    private final JsonMapper json;

    @Override
    public boolean available() {
        return client.configured();
    }

    @Override
    public Completion complete(Request request) {
        return run(request, meter -> {
            var body = body(request, messages(request));
            var res = client.complete(body);
            meter.add(res);
            return new Completion(trim(content(res)), meter.model(res), meter.usage(), List.of(), null, null);
        });
    }

    @Override
    public Completion converse(
            Request request, List<AssistantTool> tools, ToolContext context, @Nullable StreamSink sink) {
        var offered = new LinkedHashMap<String, AssistantTool>();
        tools.stream()
                .filter(t -> context.role().grants(t.permission()))
                .forEach(t -> offered.putIfAbsent(t.name(), t));
        return run(request, meter -> {
            var messages = messages(request);
            var runs = new ArrayList<ToolRun>();
            String screen = null;
            for (int round = 0; round <= props.maxToolRounds(); round++) {
                var body = body(request, messages);
                var toolsAllowed = round < props.maxToolRounds() && !offered.isEmpty();
                if (toolsAllowed) {
                    body.set("tools", definitions(offered.values()));
                    body.put("tool_choice", "auto");
                }
                var res = sink == null ? client.complete(body) : client.stream(body, sink::delta);
                meter.add(res);
                var message = res.path("choices").path(0).path("message");
                var calls = message.path("tool_calls");
                if (!toolsAllowed || !calls.isArray() || calls.isEmpty()) {
                    meter.tools(runs.size());
                    return new Completion(trim(content(res)), meter.model(res), meter.usage(), runs, null, screen);
                }
                var assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.put(
                        "content",
                        message.path("content").isString()
                                ? message.get("content").asString()
                                : "");
                assistant.set("tool_calls", calls);
                for (var call : calls) {
                    var name = call.path("function").path("name").asString("");
                    var tool = offered.get(name);
                    var args = arguments(call);
                    var toolCall = new AssistantTool.Call(
                            context.merchantId(), context.userId(), context.role(), args, context.locale());
                    if (tool != null && tool.write()) {
                        var pending = new PendingAction(
                                name,
                                json.convertValue(args, new TypeReference<Map<String, Object>>() {}),
                                tool.preview(toolCall));
                        var run = new ToolRun(name, pending.preview(), true);
                        runs.add(run);
                        if (sink != null) {
                            sink.tool(run);
                        }
                        meter.tools(runs.size());
                        return new Completion(
                                trim(content(res)), meter.model(res), meter.usage(), runs, pending, tool.screen());
                    }
                    var outcome = execute(tool, name, toolCall);
                    runs.add(outcome.run());
                    if (sink != null) {
                        sink.tool(outcome.run());
                    }
                    if (tool != null && outcome.run().ok() && tool.screen() != null) {
                        screen = tool.screen();
                    }
                    messages.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.path("id").asString(""))
                            .put("content", outcome.content());
                }
            }
            throw new IllegalStateException("unreachable: the last round offers no tools");
        });
    }

    private record Outcome(ToolRun run, String content) {}

    /** Runs a read tool as the caller; every refusal or failure becomes an error the model reads. */
    private Outcome execute(@Nullable AssistantTool tool, String name, AssistantTool.Call call) {
        if (tool == null) {
            return failed(name, "unknown_tool", "No tool called " + name + " is available to this person.");
        }
        try {
            access.require(call.merchantId(), tool.permission());
            var result = tool.run(call);
            var content = json.writeValueAsString(result.data());
            if (content.length() > MAX_TOOL_RESULT) {
                content = content.substring(0, MAX_TOOL_RESULT) + "…(truncated)";
            }
            return new Outcome(new ToolRun(name, result.summary(), true), content);
        } catch (AccessDeniedException e) {
            return failed(name, "forbidden", String.valueOf(e.getMessage()));
        } catch (NotFound e) {
            return failed(name, "not_found", String.valueOf(e.getMessage()));
        } catch (RuleViolation e) {
            return failed(
                    name,
                    "invalid",
                    e.getViolations().stream()
                            .map(v -> v.field() + ": " + v.message())
                            .toList()
                            .toString());
        } catch (Conflict e) {
            return failed(name, e.getCode(), String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Assistant tool {} failed: {}", name, e.toString());
            return failed(name, "tool_failed", "The tool failed; say so and don't guess.");
        }
    }

    private Outcome failed(String name, String code, String detail) {
        var content = json.writeValueAsString(Map.of("error", code, "detail", detail));
        return new Outcome(new ToolRun(name, name + ": " + code, false), content);
    }

    private JsonNode arguments(JsonNode call) {
        var raw = call.path("function").path("arguments");
        try {
            var parsed = raw.isString() && !raw.asString().isBlank() ? json.readTree(raw.asString()) : raw;
            return parsed != null && parsed.isObject() ? parsed : json.createObjectNode();
        } catch (JacksonException _) {
            return json.createObjectNode();
        }
    }

    private ArrayNode definitions(Iterable<AssistantTool> tools) {
        var out = json.createArrayNode();
        for (var tool : tools) {
            var fn = out.addObject().put("type", "function").putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            var params = fn.putObject("parameters");
            params.put("type", "object");
            params.set("properties", json.valueToTree(tool.parameters()));
            params.set("required", json.valueToTree(tool.required()));
        }
        return out;
    }

    private ArrayNode messages(Request request) {
        var out = json.createArrayNode();
        request.messages().forEach(m -> out.addObject().put("role", m.role()).put("content", m.content()));
        return out;
    }

    private ObjectNode body(Request request, ArrayNode messages) {
        var body = json.createObjectNode();
        var model = props.modelFor(request.feature());
        if (model != null) {
            body.put("model", model);
        }
        body.set("messages", messages);
        body.put("max_tokens", request.maxTokens());
        if (request.json()) {
            body.putObject("response_format").put("type", "json_object");
        }
        return body;
    }

    private static String content(JsonNode res) {
        var c = res.path("choices").path(0).path("message").path("content");
        return c.isString() ? c.asString() : "";
    }

    private static String trim(String s) {
        var t = s.strip();
        return t.length() > MAX_ANSWER ? t.substring(0, MAX_ANSWER) : t;
    }

    /** Guard, feature scope, metering, budget charge and the usage record around one request. */
    private Completion run(Request request, Function<Meter, Completion> work) {
        if (!client.configured()) {
            throw new AiUnavailable("AI is not configured here (OPENROUTER_API_KEY is not set).");
        }
        var meter = new Meter(client.model(), props.modelFor(request.feature()));
        var outcome = "ok";
        try {
            budgets.admit(request.caller());
            return ScopedValue.where(AiScope.FEATURE, request.feature()).call(() -> work.apply(meter));
        } catch (AiRateLimited e) {
            outcome = "rate_limited";
            throw e;
        } catch (AiUnavailable e) {
            outcome = "unavailable";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            if (meter.calls > 0) {
                budgets.charge(request.caller(), meter.prompt + meter.completion);
            }
            record(request, meter, outcome);
        }
    }

    private void record(Request request, Meter meter, String outcome) {
        var usage = meter.usage();
        try {
            usageLog.record(new AiUsageLog.Entry(
                    request.feature().code(),
                    request.caller().personId(),
                    request.caller().merchantId(),
                    client.name(),
                    meter.model,
                    request.prompt(),
                    usage.modelCalls(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.costUsd() == null ? null : Math.round(usage.costUsd() * 1_000_000d),
                    usage.latencyMs(),
                    meter.toolRuns,
                    outcome));
        } catch (RuntimeException e) {
            log.warn("AI usage not recorded: {}", e.toString());
        }
    }

    /** Tokens, cost and latency summed over one request's model calls. */
    static final class Meter {
        private final long started = System.nanoTime();
        private String model;
        private int calls;
        private int toolRuns;
        private long prompt;
        private long completion;
        private @Nullable Double cost;

        Meter(String defaultModel, @Nullable String requested) {
            this.model = requested == null ? defaultModel : requested;
        }

        void add(JsonNode res) {
            calls++;
            var u = res.path("usage");
            prompt += u.path("prompt_tokens").asLong(0);
            completion += u.path("completion_tokens").asLong(0);
            if (u.path("cost").isNumber()) {
                cost = (cost == null ? 0 : cost) + u.get("cost").asDouble();
            }
        }

        void tools(int runs) {
            toolRuns = runs;
        }

        /** The model that answered (OpenRouter names it), else the one asked for. */
        String model(JsonNode res) {
            if (res.path("model").isString() && !res.get("model").asString().isBlank()) {
                model = res.get("model").asString();
            }
            return model;
        }

        Usage usage() {
            return new Usage(
                    calls,
                    prompt,
                    completion,
                    cost == null ? null : Math.round(cost * 1_000_000d) / 1_000_000d,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
