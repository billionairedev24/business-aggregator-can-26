package ca.northline.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** S-112: what goes out over OTLP is redacted and linked to the current trace. */
class OtlpLogAppenderTest {

    final List<LogRecordData> exported = new CopyOnWriteArrayList<>();

    @Test
    void exportsARedactedRecordInTheCurrentTrace() {
        var exporter = new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> logs) {
                exported.addAll(logs);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        try (var sdk = OpenTelemetrySdk.builder()
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                        .build())
                .build()) {
            var appender = new OtlpLogAppender(sdk);
            appender.setContext(new LoggerContext());
            appender.start();
            var logger = new LoggerContext().getLogger("ca.northline.test");
            var event = new LoggingEvent(
                    "fqcn",
                    logger,
                    Level.WARN,
                    "Code for {}: verification code 482913",
                    new IllegalArgumentException("bad card 4242 4242 4242 4242"),
                    new Object[] {"amara@example.ca"});
            event.setMDCPropertyMap(Map.of("eventId", "01J9ZD3V0000000000000EVT01", "authorization", "Bearer x"));
            var span = SpanContext.create(
                    "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
            try (var _ = Span.wrap(span).makeCurrent()) {
                appender.doAppend(event);
            }
        }

        assertThat(exported).hasSize(1);
        var record = exported.getFirst();
        assertThat(record.getBodyValue().asString()).isEqualTo("Code for [EMAIL]: verification code [CODE]");
        assertThat(record.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(record.getSpanContext().getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("authorization"))).isEqualTo(Redactor.MASK);
        assertThat(record.getAttributes().get(AttributeKey.stringKey("eventId"))).isEqualTo("01J9ZD3V0000000000000EVT01");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("exception.message")))
                .isEqualTo("bad card [CARD …4242]");
        assertThat(record.getAttributes().get(AttributeKey.stringKey("exception.stacktrace")))
                .doesNotContain("4242 4242");
    }
}
