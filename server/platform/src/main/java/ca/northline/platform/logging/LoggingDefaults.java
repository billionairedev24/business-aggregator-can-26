package ca.northline.platform.logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * S-112: structured JSON logs on the console, with {@link RedactingJsonMembersCustomizer}, for every app that uses
 * this library. {@code LOG_FORMAT} picks the format: {@code ecs} (Elastic Common Schema, the default under the
 * deployed profiles), {@code logstash}, {@code gelf}, or {@code text} for Spring Boot's plain lines (the default under
 * {@code local} and {@code test}, where people read them). The trace and span ids become ECS's {@code trace.id} /
 * {@code span.id}. Lowest precedence: {@code logging.structured.*} in an app's configuration still wins.
 */
public final class LoggingDefaults implements EnvironmentPostProcessor, Ordered {

    static final String NAME = "northline-logging-defaults";
    private static final Set<String> FORMATS = Set.of("ecs", "logstash", "gelf");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(NAME)) {
            return;
        }
        var props = new LinkedHashMap<String, Object>();
        props.put("logging.structured.json.customizer", RedactingJsonMembersCustomizer.class.getName());
        props.put("logging.structured.json.rename.traceId", "trace.id");
        props.put("logging.structured.json.rename.spanId", "span.id");
        var deployed = environment.matchesProfiles("cloud | dev | staging | prod");
        var format = environment.getProperty("LOG_FORMAT", deployed ? "ecs" : "text")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (FORMATS.contains(format)) {
            props.put("logging.structured.format.console", format);
        } else if (!format.equals("text") && StringUtils.hasText(format)) {
            throw new IllegalStateException(
                    "LOG_FORMAT must be ecs, logstash, gelf or text (got '" + format + "'): docs/runbooks/logging.md");
        }
        for (var profile : environment.getActiveProfiles()) {
            if (Set.of("dev", "staging", "prod").contains(profile)) {
                props.put("logging.structured.ecs.service.environment", profile);
            }
        }
        environment.getPropertySources().addLast(new MapPropertySource(NAME, props));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
