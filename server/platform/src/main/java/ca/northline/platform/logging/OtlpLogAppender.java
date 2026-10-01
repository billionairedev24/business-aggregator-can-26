package ca.northline.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.Objects;

/**
 * Logback → OpenTelemetry logs (S-112): every log event becomes an OTLP log record with the current trace context
 * (so the backend links it to its trace), the logger, thread, MDC entries and exception as attributes — each string
 * through {@link Redactor} first. Spring Boot exports the records ({@code management.logging.export.enabled}, the
 * Collector's {@code /v1/logs}); {@link LoggingAutoConfiguration} attaches this appender to the root logger.
 * Northline's own appender instead of the OpenTelemetry Logback instrumentation: that one sends the raw message.
 */
public final class OtlpLogAppender extends AppenderBase<ILoggingEvent> {

    static final String NAME = "NORTHLINE_OTLP";
    private static final AttributeKey<String> LOGGER = AttributeKey.stringKey("logger.name");
    private static final AttributeKey<String> THREAD = AttributeKey.stringKey("thread.name");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final Logger logger;

    public OtlpLogAppender(OpenTelemetry openTelemetry) {
        this.logger = openTelemetry.getLogsBridge().get("ca.northline.logging");
        setName(NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        var record = logger.logRecordBuilder()
                .setTimestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .setContext(Context.current())
                .setSeverity(severity(event.getLevel()))
                .setSeverityText(event.getLevel().toString())
                .setBody(Objects.requireNonNullElse(Redactor.redact(event.getFormattedMessage()), ""))
                .setAttribute(LOGGER, event.getLoggerName())
                .setAttribute(THREAD, event.getThreadName());
        event.getMDCPropertyMap().forEach((key, value) -> {
            var redacted = Redactor.redact(key, value);
            if (redacted != null) {
                record.setAttribute(AttributeKey.stringKey(key), redacted);
            }
        });
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            record.setAttribute(EXCEPTION_TYPE, error.getClassName());
            var message = Redactor.redact(error.getMessage());
            if (message != null) {
                record.setAttribute(EXCEPTION_MESSAGE, message);
            }
            record.setAttribute(
                    EXCEPTION_STACKTRACE,
                    Objects.requireNonNullElse(Redactor.redact(ThrowableProxyUtil.asString(error)), ""));
        }
        record.emit();
    }

    static Severity severity(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> Severity.ERROR;
            case Level.WARN_INT -> Severity.WARN;
            case Level.INFO_INT -> Severity.INFO;
            case Level.DEBUG_INT -> Severity.DEBUG;
            case Level.TRACE_INT -> Severity.TRACE;
            default -> Severity.UNDEFINED_SEVERITY_NUMBER;
        };
    }
}
