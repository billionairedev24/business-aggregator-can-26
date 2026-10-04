package ca.northline.golive.adapters;

import ca.northline.golive.application.AlertSignals;
import ca.northline.golive.application.GoLiveProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The alert-signal adapter: Prometheus-compatible when {@code GO_LIVE_PROMETHEUS_URL} (default: the console's
 * {@code CONSOLE_HEALTH_PROMETHEUS_URL}) is set, otherwise none — the alert gates are then recorded by hand. The app
 * starts either way.
 */
@Configuration(proxyBeanMethods = false)
class GoLiveAdaptersConfig {

    @Bean
    AlertSignals goLiveAlertSignals(GoLiveProperties props) {
        return props.prometheus().url().isBlank()
                ? () -> AlertSignals.Reading.OFF
                : new PrometheusAlertSignals(props.prometheus());
    }
}
