package ca.northline.platform.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds {@code classpath:northline/observability-defaults.yml} as the <em>lowest</em>-precedence property source of
 * every app that uses this library (api, auth, bff, worker): trace and metric export over OTLP behind one switch
 * ({@code OTEL_EXPORT_ENABLED}), Kafka observations, histograms for the dashboards, resource attributes. An app's own
 * {@code application*.yml}, the environment and the standard {@code OTEL_*} variables (mapped by Spring Boot) all win,
 * so the file only says what is the same everywhere.
 */
public final class ObservabilityDefaults implements EnvironmentPostProcessor, Ordered {

    static final String NAME = "northline-observability-defaults";
    static final String LOCATION = "northline/observability-defaults.yml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        if (sources.contains(NAME)) {
            return;
        }
        try {
            var loaded = new YamlPropertySourceLoader().load(NAME, new ClassPathResource(LOCATION));
            loaded.forEach(sources::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + LOCATION, e);
        }
    }

    /** After the application's own configuration files are loaded, so {@code addLast} really is last. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
