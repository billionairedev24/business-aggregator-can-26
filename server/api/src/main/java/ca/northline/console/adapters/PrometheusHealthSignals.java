package ca.northline.console.adapters;

import ca.northline.console.application.HealthSignals;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * {@link HealthSignals} from a Prometheus-compatible query API ({@code GET <url>/api/v1/query?query=…}, the instant
 * query of the Prometheus HTTP API, which Grafana Cloud / Mimir and the AWS, Google and Azure managed services all
 * answer). One query per signal, from configuration ({@link ConsoleHealthProperties.Prometheus#queries}); a query that
 * fails, times out or returns no sample makes that signal {@code unknown}, never the overview.
 */
@Slf4j
class PrometheusHealthSignals implements HealthSignals {

    private final ConsoleHealthProperties.Prometheus props;
    private final RestClient http;

    PrometheusHealthSignals(ConsoleHealthProperties.Prometheus props) {
        this.props = props;
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(props.timeout());
        var builder = RestClient.builder().requestFactory(requests);
        if (!props.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        this.http = builder.build();
    }

    @Override
    public List<Reading> read() {
        return Arrays.stream(Signal.values())
                .filter(s -> s != Signal.COURIER_APP)
                .map(this::read)
                .toList();
    }

    private Reading read(Signal signal) {
        var query = props.query(signal.code());
        if (query == null || query.isBlank() || props.url().isBlank()) {
            return Reading.unknown(signal);
        }
        try {
            var body = http.get()
                    .uri(uri(query))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            var value = sample(body);
            if (value == null) {
                return Reading.unknown(signal);
            }
            var limit = props.degradedAbove().get(signal.code());
            return new Reading(signal, value, limit != null && value > limit ? Status.DEGRADED : Status.OK);
        } catch (RuntimeException e) {
            log.warn("Console health: {} unreadable from {}: {}", signal.code(), props.url(), e.getMessage());
            return Reading.unknown(signal);
        }
    }

    private URI uri(String query) {
        var base =
                props.url().endsWith("/") ? props.url().substring(0, props.url().length() - 1) : props.url();
        return UriComponentsBuilder.fromUriString(base + "/api/v1/query")
                .queryParam("query", "{q}")
                .encode()
                .buildAndExpand(query)
                .toUri();
    }

    /** {@code {"status":"success","data":{"resultType":"vector"|"scalar","result":…}}} → the first sample's value. */
    static @Nullable Double sample(@Nullable JsonNode body) {
        if (body == null || !"success".equals(body.path("status").asString(""))) {
            return null;
        }
        var data = body.path("data");
        var result = data.path("result");
        var pair = "scalar".equals(data.path("resultType").asString(""))
                ? result
                : result.isArray() && !result.isEmpty() ? result.get(0).path("value") : null;
        if (pair == null || !pair.isArray() || pair.size() < 2) {
            return null;
        }
        try {
            var v = Double.parseDouble(pair.get(1).asString(""));
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
