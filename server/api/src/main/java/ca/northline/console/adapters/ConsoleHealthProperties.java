package ca.northline.console.adapters;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.console.health.*} (S-91): where the overview's system health comes from.
 *
 * @param provider {@code none} (every signal unknown) or {@code prometheus} ({@code CONSOLE_HEALTH_PROVIDER})
 * @param prometheus the query API of a Prometheus-compatible store
 */
@ConfigurationProperties("northline.console.health")
public record ConsoleHealthProperties(@DefaultValue("none") Provider provider, Prometheus prometheus) {

    public enum Provider {
        NONE,
        PROMETHEUS
    }

    /**
     * @param url base URL of the Prometheus HTTP API — the part before {@code /api/v1/query}
     *     ({@code CONSOLE_HEALTH_PROMETHEUS_URL}, e.g. {@code https://prometheus-prod-…grafana.net/api/prom})
     * @param token bearer token, when the store needs one ({@code CONSOLE_HEALTH_PROMETHEUS_TOKEN}, a secret); empty =
     *     none (a sidecar or the network does the authentication: SigV4 proxy for Amazon, the GMP frontend for Google)
     * @param timeout per query
     * @param queries PromQL per signal code ({@code api_p95}, {@code search_p95}, {@code kafka_lag}, {@code stripe},
     *     {@code tracking_streams}); each must return one sample
     * @param degradedAbove a reading above this is {@code degraded} (per signal code; none = always {@code ok})
     */
    public record Prometheus(
            @DefaultValue("") String url,
            @DefaultValue("") String token,
            @DefaultValue("3s") Duration timeout,
            Map<String, String> queries,
            Map<String, Double> degradedAbove) {

        public Prometheus {
            queries = Map.copyOf(queries);
            degradedAbove = Map.copyOf(degradedAbove);
        }

        public @Nullable String query(String code) {
            return queries.get(code);
        }
    }
}
