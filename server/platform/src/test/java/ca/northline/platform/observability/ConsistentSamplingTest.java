package ca.northline.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConsistentSamplingTest {

    static final String LOW_TRACE = "00000000000000000000000000000001"; // under any ratio > 0
    static final String HIGH_TRACE = "00000000000000007fffffffffffffff"; // above any ratio < 1 (low 64 bits decide)

    @Test
    void aCallerCannotForceSamplingWithTheSampledFlag() {
        var sampler = ConsistentSampling.sampler(0.1);

        assertThat(decide(sampler, remoteParent(HIGH_TRACE, true), HIGH_TRACE)).isEqualTo(SamplingDecision.DROP);
        assertThat(decide(sampler, remoteParent(LOW_TRACE, false), LOW_TRACE))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void everyServiceReachesTheSameDecisionForTheSameTrace() {
        var bff = ConsistentSampling.sampler(0.25);
        var api = ConsistentSampling.sampler(0.25);
        var traces = IntStream.range(0, 200)
                .mapToObj(i -> "%032x".formatted((long) i * 0x9E3779B97F4A7C15L))
                .toList();
        for (var trace : traces) {
            var atEdge = decide(bff, Context.root(), trace);
            assertThat(decide(api, remoteParent(trace, atEdge == SamplingDecision.RECORD_AND_SAMPLE), trace))
                    .as(trace)
                    .isEqualTo(atEdge);
        }
    }

    @Test
    void aLocalChildFollowsItsParent() {
        var sampler = ConsistentSampling.sampler(0.0);
        var parent =
                SpanContext.create(LOW_TRACE, "00000000000000aa", TraceFlags.getSampled(), TraceState.getDefault());
        var context = Context.root().with(Span.wrap(parent));

        assertThat(decide(sampler, context, LOW_TRACE)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void theRatioIsClamped() {
        assertThat(decide(ConsistentSampling.sampler(7), Context.root(), HIGH_TRACE))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decide(ConsistentSampling.sampler(-1), Context.root(), LOW_TRACE))
                .isEqualTo(SamplingDecision.DROP);
    }

    private static Context remoteParent(String traceId, boolean sampled) {
        var flags = sampled ? TraceFlags.getSampled() : TraceFlags.getDefault();
        return Context.root()
                .with(Span.wrap(SpanContext.createFromRemoteParent(
                        traceId, "00000000000000bb", flags, TraceState.getDefault())));
    }

    private static SamplingDecision decide(
            io.opentelemetry.sdk.trace.samplers.Sampler sampler, Context parent, String traceId) {
        return sampler.shouldSample(parent, traceId, "GET /api", SpanKind.SERVER, Attributes.empty(), List.of())
                .getDecision();
    }
}
