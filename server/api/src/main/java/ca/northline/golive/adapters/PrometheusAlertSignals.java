package ca.northline.golive.adapters;

import ca.northline.golive.application.AlertSignals;
import ca.northline.golive.application.GoLiveProperties;
import java.net.http.HttpClient;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * {@link AlertSignals} from the rules endpoint of the Prometheus HTTP API ({@code GET <url>/api/v1/rules?type=alert}),
 * which Prometheus, Grafana Cloud / Mimir and the managed services answer for the rules they evaluate: Northline's
 * alerting rules ({@code rulesGroupPrefix} groups) and those of them firing with the paging severity. One call per
 * checklist read; any failure reads as unknown.
 */
@Slf4j
class PrometheusAlertSignals implements AlertSignals {

    private final GoLiveProperties.Prometheus props;
    private final RestClient http;

    PrometheusAlertSignals(GoLiveProperties.Prometheus props) {
        this.props = props;
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(props.timeout());
        var builder = RestClient.builder()
                .requestFactory(requests)
                .baseUrl(props.url().replaceAll("/+$", ""));
        if (!props.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        this.http = builder.build();
    }

    @Override
    public Reading read() {
        try {
            var body = http.get()
                    .uri("/api/v1/rules?type=alert")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !"success".equals(body.path("status").asString(""))) {
                return new Reading(true, null, null, java.util.List.of());
            }
            var rules = 0;
            var firing = 0;
            var names = new ArrayList<String>();
            for (var group : body.path("data").path("groups")) {
                if (!group.path("name").asString("").startsWith(props.rulesGroupPrefix())) {
                    continue;
                }
                for (var rule : group.path("rules")) {
                    if (!"alerting".equals(rule.path("type").asString(""))) {
                        continue;
                    }
                    rules++;
                    if ("firing".equals(rule.path("state").asString(""))
                            && props.pagingSeverity()
                                    .equals(rule.path("labels").path("severity").asString(""))) {
                        firing++;
                        if (names.size() < 10) {
                            names.add(rule.path("name").asString(""));
                        }
                    }
                }
            }
            return new Reading(true, rules, firing, names);
        } catch (RuntimeException e) {
            log.warn("go-live: the alert rules couldn't be read from {}: {}", props.url(), e.toString());
            return new Reading(true, null, null, java.util.List.of());
        }
    }
}
