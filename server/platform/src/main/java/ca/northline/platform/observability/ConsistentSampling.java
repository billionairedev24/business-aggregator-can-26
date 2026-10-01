package ca.northline.platform.observability;

import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * One sampling decision per trace, taken the same way by every service: a trace is kept when its trace id falls under
 * the ratio ({@code management.tracing.sampling.probability}, {@code OTEL_TRACES_SAMPLER_ARG}). A span whose parent is
 * in the same process follows its parent; a span whose parent came from outside (a browser's {@code traceparent}, the
 * BFF, a Kafka header) is decided on its trace id again, ignoring the caller's "sampled" flag.
 *
 * <p>Why: the browser starts traces (S-111), so the public edge must not let anyone force 100 % sampling by sending
 * {@code -01}. Because the rule is a pure function of the trace id, the BFF, the api and the worker reach the same
 * answer for the same trace — whole traces are kept or dropped, never halves — as long as they share the ratio (the
 * chart sets it once for every app).
 */
public final class ConsistentSampling {

    private ConsistentSampling() {}

    public static Sampler sampler(double probability) {
        var clamped = Math.clamp(probability, 0.0, 1.0);
        var ratio = clamped >= 1.0 ? Sampler.alwaysOn() : Sampler.traceIdRatioBased(clamped);
        return Sampler.parentBasedBuilder(ratio)
                .setRemoteParentSampled(ratio)
                .setRemoteParentNotSampled(ratio)
                .build();
    }
}
