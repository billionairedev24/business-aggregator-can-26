package ca.northline.golive.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.go-live.*} (S-118, docs/runbooks/go-live.md § Configuration).
 *
 * @param minPilotBusinesses pilot businesses that must be ready before a market launches
 *     ({@code GO_LIVE_MIN_PILOT_BUSINESSES})
 * @param recordMaxAge how long a manual gate's record counts; older = pending again ({@code GO_LIVE_RECORD_MAX_AGE})
 * @param requestTtl how long a launch request waits for its second admin ({@code GO_LIVE_REQUEST_TTL})
 * @param oncallHorizon how far ahead the on-call rota must be covered without a gap
 * @param prometheus where the alert-rule and firing-alert gates read (a Prometheus-compatible HTTP API); blank url =
 *     those gates are recorded by hand
 */
@ConfigurationProperties("northline.go-live")
public record GoLiveProperties(
        @DefaultValue("10") int minPilotBusinesses,
        @DefaultValue("P14D") Duration recordMaxAge,
        @DefaultValue("PT24H") Duration requestTtl,
        @DefaultValue("P14D") Duration oncallHorizon,
        @DefaultValue Prometheus prometheus) {

    /**
     * @param url base URL before {@code /api/v1/…} ({@code GO_LIVE_PROMETHEUS_URL}, default the console's
     *     {@code CONSOLE_HEALTH_PROMETHEUS_URL})
     * @param token bearer token ({@code GO_LIVE_PROMETHEUS_TOKEN}, a secret; default the console's)
     * @param rulesGroupPrefix the alert rule groups that count as Northline's (S-113: {@code northline-…})
     * @param pagingSeverity the {@code severity} label of alerts that page someone (S-113: {@code page})
     */
    public record Prometheus(
            @DefaultValue("") String url,
            @DefaultValue("") String token,
            @DefaultValue("3s") Duration timeout,
            @DefaultValue("northline") String rulesGroupPrefix,
            @DefaultValue("page") String pagingSeverity) {}
}
