package ca.northline.ai.application;

import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.LlmClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The port as every module sees it: whichever adapter {@code northline.ai.provider} chose, wrapped with
 *
 * <ul>
 *   <li><b>redaction</b> — every message's content goes through {@link PrivacyRedactor} (the S-112 logging redactor plus
 *       SINs and bank accounts) before it leaves;
 *   <li><b>traces and latency</b> — an observation {@code northline.ai.completion} (a span, and the timer
 *       {@code northline_ai_completion_seconds}) tagged provider, model, feature, streamed and outcome;
 *   <li><b>tokens and cost</b> — counters {@code northline.ai.tokens} (kind prompt|completion) and
 *       {@code northline.ai.cost} (USD, OpenRouter's own figure), and the per-call distribution
 *       {@code northline.ai.call.cost}, by provider, model and feature.
 * </ul>
 *
 * Tags never carry ids, names or text (docs/runbooks/ai.md § Dashboards).
 */
@RequiredArgsConstructor
public final class ObservedLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean configured() {
        return delegate.configured();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public JsonNode complete(ObjectNode request) {
        return observe(request, false, () -> delegate.complete(redact(request)));
    }

    @Override
    public JsonNode stream(ObjectNode request, Consumer<String> onDelta) {
        return observe(request, true, () -> delegate.stream(redact(request), onDelta));
    }

    /** Masks the content of every message in place (the request is built for this one call). */
    static ObjectNode redact(ObjectNode request) {
        for (var message : request.path("messages")) {
            if (message instanceof ObjectNode m && m.path("content").isString()) {
                m.put("content", PrivacyRedactor.redact(m.get("content").asString()));
            }
        }
        return request;
    }

    private JsonNode observe(ObjectNode request, boolean streamed, Supplier<JsonNode> call) {
        var model = request.path("model").isString() ? request.get("model").asString() : delegate.model();
        var feature = AiScope.feature();
        var observation = Observation.createNotStarted("northline.ai.completion", observations)
                .lowCardinalityKeyValue("provider", delegate.name())
                .lowCardinalityKeyValue("model", model)
                .lowCardinalityKeyValue("feature", feature)
                .lowCardinalityKeyValue("streamed", Boolean.toString(streamed))
                .start();
        var outcome = "ok";
        try (var _ = observation.openScope()) {
            var result = call.get();
            usage(result, model, feature);
            return result;
        } catch (AiRateLimited e) {
            outcome = "rate_limited";
            observation.error(e);
            throw e;
        } catch (AiUnavailable e) {
            outcome = e.getMessage() != null && e.getMessage().contains("timed out") ? "timeout" : "unavailable";
            observation.error(e);
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            observation.error(e);
            throw e;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    private void usage(JsonNode result, String model, String feature) {
        var u = result.path("usage");
        if (!u.isObject()) {
            return;
        }
        String[] tags = {"provider", delegate.name(), "model", model, "feature", feature};
        Counter.builder("northline.ai.tokens")
                .description("Model tokens, by kind")
                .tags(tags)
                .tag("kind", "prompt")
                .register(meters)
                .increment((double) u.path("prompt_tokens").asLong(0));
        Counter.builder("northline.ai.tokens")
                .description("Model tokens, by kind")
                .tags(tags)
                .tag("kind", "completion")
                .register(meters)
                .increment((double) u.path("completion_tokens").asLong(0));
        if (u.path("cost").isNumber()) {
            var usd = u.get("cost").asDouble();
            Counter.builder("northline.ai.cost")
                    .description("Model spend in USD as reported by the provider")
                    .baseUnit("usd")
                    .tags(tags)
                    .register(meters)
                    .increment(usd);
            DistributionSummary.builder("northline.ai.call.cost")
                    .description("Cost of one model call in USD")
                    .baseUnit("usd")
                    .tags(tags)
                    .publishPercentiles(0.5, 0.95)
                    .register(meters)
                    .record(usd);
        }
    }
}
