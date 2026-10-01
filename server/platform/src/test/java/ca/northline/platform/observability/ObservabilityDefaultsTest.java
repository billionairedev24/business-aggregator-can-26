package ca.northline.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ObservabilityDefaultsTest {

    @Test
    void addsTheDefaultsBelowEverythingTheAppConfigures() {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new MapPropertySource(
                        "application.yml", Map.of("management.tracing.sampling.probability", "0.2")));

        new ObservabilityDefaults().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().stream().toList().getLast().getName())
                .isEqualTo(ObservabilityDefaults.NAME);
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.2");
        assertThat(env.getProperty("spring.kafka.template.observation-enabled")).isEqualTo("true");
        assertThat(env.getProperty("management.opentelemetry.resource-attributes.service.namespace"))
                .isEqualTo("northline");
    }

    @Test
    void exportIsOffUntilOtelExportEnabled() {
        var off = new StandardEnvironment();
        new ObservabilityDefaults().postProcessEnvironment(off, new SpringApplication());
        assertThat(off.getProperty("management.tracing.export.enabled")).isEqualTo("false");

        var on = new StandardEnvironment();
        on.getPropertySources().addFirst(new MapPropertySource("env", Map.of("OTEL_EXPORT_ENABLED", "true")));
        new ObservabilityDefaults().postProcessEnvironment(on, new SpringApplication());
        assertThat(on.getProperty("management.tracing.export.enabled")).isEqualTo("true");
        assertThat(on.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("true");
    }

    @Test
    void addsItselfOnce() {
        var env = new StandardEnvironment();
        new ObservabilityDefaults().postProcessEnvironment(env, new SpringApplication());
        new ObservabilityDefaults().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().stream()
                        .filter(s -> s.getName().equals(ObservabilityDefaults.NAME))
                        .count())
                .isEqualTo(1);
    }
}
