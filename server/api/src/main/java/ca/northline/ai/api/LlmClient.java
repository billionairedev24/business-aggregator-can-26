package ca.northline.ai.api;

import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Port: a chat-completion model with tool calling, chosen by {@code northline.ai.provider} ({@code AI_PROVIDER}):
 *
 * <ul>
 *   <li>{@code openrouter}: OpenRouter's OpenAI-compatible API ({@code OPENROUTER_API_KEY}, docs/runbooks/ai.md).
 *   <li>{@code fake}: deterministic answers without a network or a key (local, test, dev). Refused under staging/prod.
 * </ul>
 *
 * <p>Requests and answers use the OpenAI chat-completions shape, the lingua franca of model gateways: {@code {model?,
 * messages, tools?, tool_choice?, max_tokens?, response_format?}} in; {@code {model, choices: [{message: {content,
 * tool_calls}, finish_reason}], usage: {prompt_tokens, completion_tokens, cost?}}} out. The adapter adds what is
 * provider-specific (attribution headers, usage accounting, data policy). The bean every module sees is wrapped with
 * redaction, metrics and traces ({@code ai.application.ObservedLlmClient}). Failures: {@link AiUnavailable} (503
 * {@code ai_unavailable}: unconfigured, unreachable, timed out, provider error) or {@link AiRateLimited} (429
 * {@code ai_rate_limited}).
 *
 * <p>Features never call this directly: they go through {@link AiCompletions}, which adds budgets and usage records.
 */
public interface LlmClient {

    /** The provider's key, e.g. {@code openrouter}; a metrics tag. */
    String name();

    /** False when the provider lacks what it needs to answer (no API key): every AI feature answers 503. */
    boolean configured();

    /** The model a request goes to when it names none. */
    String model();

    /** One completion. */
    JsonNode complete(ObjectNode request);

    /**
     * One completion, streamed: each piece of answer text goes to {@code onDelta} as it arrives; the assembled answer
     * comes back in the same shape as {@link #complete} (tool-call fragments joined).
     */
    JsonNode stream(ObjectNode request, Consumer<String> onDelta);
}
