package ca.northline.console.adapters;

import ca.northline.console.application.HealthSignals;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the {@link HealthSignals} adapter by {@code northline.console.health.provider} ({@code CONSOLE_HEALTH_PROVIDER}). */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleHealthProperties.class)
class HealthSignalsConfiguration {

    @Bean
    HealthSignals healthSignals(ConsoleHealthProperties props) {
        return switch (props.provider()) {
            case NONE -> {
                log.info(
                        "Console health: none (CONSOLE_HEALTH_PROVIDER) — the overview shows system health as unknown.");
                yield () -> Arrays.stream(HealthSignals.Signal.values())
                        .filter(s -> s != HealthSignals.Signal.COURIER_APP)
                        .map(HealthSignals.Reading::unknown)
                        .toList();
            }
            case PROMETHEUS -> {
                if (props.prometheus().url().isBlank()) {
                    log.warn(
                            "Console health: prometheus without CONSOLE_HEALTH_PROMETHEUS_URL — every signal unknown.");
                } else {
                    log.info(
                            "Console health: Prometheus query API at {}.",
                            props.prometheus().url());
                }
                yield new PrometheusHealthSignals(props.prometheus());
            }
        };
    }
}
