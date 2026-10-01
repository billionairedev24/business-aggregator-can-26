package ca.northline.platform.logging;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * S-112: ships the logs over OTLP when export is on ({@code management.logging.export.enabled}, i.e.
 * {@code OTEL_EXPORT_ENABLED=true}) by attaching {@link OtlpLogAppender} to Logback's root logger once the
 * application context is ready, and detaching it on shutdown.
 */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration")
@ConditionalOnClass({LoggerContext.class, OpenTelemetry.class})
@ConditionalOnProperty(name = "management.logging.export.enabled", havingValue = "true")
public class LoggingAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    OtlpLogs otlpLogs(OpenTelemetry openTelemetry) {
        return new OtlpLogs(openTelemetry);
    }

    /** Attaches / detaches the appender (Logback is configured by Spring Boot before any bean exists). */
    @Slf4j
    static final class OtlpLogs implements SmartInitializingSingleton, DisposableBean {

        private final OpenTelemetry openTelemetry;

        OtlpLogs(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
                return;
            }
            var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (root.getAppender(OtlpLogAppender.NAME) == null) {
                var appender = new OtlpLogAppender(openTelemetry);
                appender.setContext(context);
                appender.start();
                root.addAppender(appender);
                log.info("Logs: exported over OTLP, redacted (S-112)");
            }
        }

        @Override
        public void destroy() {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                var appender = root.getAppender(OtlpLogAppender.NAME);
                if (appender != null) {
                    root.detachAppender(appender);
                    appender.stop();
                }
            }
        }
    }
}
