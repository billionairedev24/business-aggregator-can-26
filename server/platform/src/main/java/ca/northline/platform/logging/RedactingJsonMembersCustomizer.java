package ca.northline.platform.logging;

import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Runs every string of a structured log line — the message, MDC entries, key-value pairs, the exception's message and
 * stack trace — through {@link Redactor} (S-112). Registered for every app by {@link LoggingDefaults}
 * ({@code logging.structured.json.customizer}), whatever the format (ECS, Logstash, GELF).
 */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(Members<Object> members) {
        ValueProcessor<Object> redact = (path, value) ->
                value instanceof CharSequence text ? Redactor.redact(path.name(), text.toString()) : value;
        members.applyingValueProcessor(redact);
    }
}
