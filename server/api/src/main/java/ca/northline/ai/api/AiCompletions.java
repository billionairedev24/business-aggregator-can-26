package ca.northline.ai.api;

import ca.northline.shared.security.MerchantRole;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What AI features call (never {@link LlmClient} directly). Every call is checked against the caller's and the business's
 * budget (429 {@link AiRateLimited}), goes to the feature's model, is metered (tokens, cost, latency) and recorded in
 * {@code ai.usage} without its content. With no model configured every call is 503 {@link AiUnavailable}.
 */
public interface AiCompletions {

    /** Whether a model is configured; the apps hide AI actions when not ({@code GET /api/v1/ai/status}). */
    boolean available();

    /** One completion without tools: drafts, classification, summaries. */
    Completion complete(Request request);

    /**
     * The bounded tool loop (at most {@code northline.ai.max-tool-rounds} rounds): the model calls {@code tools}, each
     * runs as the caller ({@link AssistantTool#permission()} is checked again on every run), and their results go back
     * to the model until it answers. A {@link AssistantTool#write()} tool is never run here: the loop stops and returns
     * it as {@link Completion#pending()} for the person to confirm. {@code sink} (nullable) sees tool runs and answer
     * text as they happen (every model call then streams).
     */
    Completion converse(Request request, List<AssistantTool> tools, ToolContext context, @Nullable StreamSink sink);

    /**
     * Who pays: budgets are per person ({@code personId}, a user id, or an opaque hashed key for a signed-out visitor)
     * and, when set, per business.
     */
    record Caller(String personId, @Nullable String merchantId) {
        public static Caller person(String userId) {
            return new Caller(userId, null);
        }

        public static Caller member(String userId, String merchantId) {
            return new Caller(userId, merchantId);
        }

        /** A platform job (trust &amp; safety screening, anomaly scans): its own daily budget, no per-minute rate. */
        public static Caller system(String job) {
            return new Caller(SYSTEM + job, null);
        }

        public static final String SYSTEM = "system:";

        public boolean isSystem() {
            return personId.startsWith(SYSTEM);
        }
    }

    /** A chat message; {@code role} = {@code system}, {@code user} or {@code assistant}. */
    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /**
     * @param prompt the versioned prompt the system message came from ({@code listing-copy@v1}), recorded with the usage
     * @param json ask for a JSON object reply ({@code response_format: json_object})
     * @param maxTokens cap on the answer's length
     */
    record Request(
            AiFeature feature, Caller caller, String prompt, List<Message> messages, boolean json, int maxTokens) {

        public Request {
            messages = List.copyOf(messages);
        }

        public static Request of(AiFeature feature, Caller caller, Prompt prompt, String system, String user) {
            return new Request(
                    feature, caller, prompt.id(), List.of(Message.system(system), Message.user(user)), false, 800);
        }

        public Request asJson() {
            return new Request(feature, caller, prompt, messages, true, maxTokens);
        }

        public Request withMaxTokens(int tokens) {
            return new Request(feature, caller, prompt, messages, json, tokens);
        }
    }

    /**
     * Where tools run: the business and the caller's membership (already authorized), the reply language and the
     * business's time zone (from its market, never a default), in which tools show times and "today".
     */
    record ToolContext(String merchantId, String userId, MerchantRole role, Locale locale, ZoneId zone) {}

    /** What a streamed conversation reports as it goes. */
    interface StreamSink {
        /** A tool ran (or was refused). */
        void tool(ToolRun run);

        /** More answer text. */
        void delta(String text);
    }

    /** One tool run: its one-line summary for the UI ("orders today → 3") and whether it succeeded. */
    record ToolRun(String tool, String summary, boolean ok) {}

    /** A write the model proposed; nothing ran. The person confirms it (S-130) or not. */
    record PendingAction(String tool, Map<String, Object> arguments, String preview) {
        public PendingAction {
            arguments = Map.copyOf(arguments);
        }
    }

    /**
     * @param costUsd OpenRouter's own figure for the calls, when reported
     */
    record Usage(
            int modelCalls,
            long promptTokens,
            long completionTokens,
            @Nullable Double costUsd,
            long latencyMs) {
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * @param text the answer (trimmed); for a JSON request the raw JSON, see {@link #json()}
     * @param screen the Studio screen the last tool relates to ({@code orders}), for a "Open orders" link
     */
    record Completion(
            String text,
            String model,
            Usage usage,
            List<ToolRun> toolRuns,
            @Nullable PendingAction pending,
            @Nullable String screen) {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        public Completion {
            toolRuns = List.copyOf(toolRuns);
        }

        /** The answer as a JSON object (Markdown fences tolerated), or empty when it isn't one. */
        public Optional<JsonNode> json() {
            var candidate = text.strip().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            try {
                var node = JSON.readTree(candidate);
                return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
            } catch (JacksonException _) {
                return Optional.empty();
            }
        }
    }
}
