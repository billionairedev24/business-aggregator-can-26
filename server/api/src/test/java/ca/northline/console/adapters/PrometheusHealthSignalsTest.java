package ca.northline.console.adapters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.console.application.HealthSignals.Reading;
import ca.northline.console.application.HealthSignals.Signal;
import ca.northline.console.application.HealthSignals.Status;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S-91: the console's system health from a Prometheus-compatible query API, against a WireMock stand-in answering as the
 * Prometheus HTTP API documents ({@code /api/v1/query}, vector and scalar results). Never run against a real store.
 */
class PrometheusHealthSignalsTest {

    static final WireMockServer PROM =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        PROM.start();
    }

    @AfterAll
    static void stop() {
        PROM.stop();
    }

    @BeforeEach
    void reset() {
        PROM.resetAll();
    }

    private static String vector(String value) {
        return """
                {"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1759338000.0,"%s"]}]}}""".formatted(value);
    }

    private static void answer(String query, String body) {
        PROM.stubFor(get(urlPathEqualTo("/prom/api/v1/query"))
                .withQueryParam("query", equalTo(query))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private PrometheusHealthSignals signals(String token) {
        return new PrometheusHealthSignals(new ConsoleHealthProperties.Prometheus(
                PROM.baseUrl() + "/prom/",
                token,
                Duration.ofSeconds(2),
                Map.of(
                        "api_p95", "histogram_quantile(0.95, api)",
                        "search_p95", "search{uri=~\"/api/v1/search.*\"}",
                        "kafka_lag", "max(lag)",
                        "stripe", "stripe_errors",
                        "tracking_streams", "sum(streams)"),
                Map.of("api_p95", 500.0, "kafka_lag", 1000.0)));
    }

    @Test
    void readsEverySignal_andMarksTheOnesAboveTheirLimit() {
        answer("histogram_quantile(0.95, api)", vector("184.2"));
        answer("search{uri=~\"/api/v1/search.*\"}", vector("92"));
        answer("max(lag)", vector("2400"));
        answer("stripe_errors", """
                {"status":"success","data":{"resultType":"scalar","result":[1759338000.0,"0"]}}""");
        answer("sum(streams)", """
                {"status":"success","data":{"resultType":"vector","result":[]}}""");

        var readings = signals("fake-prometheus-token").read();

        assertThat(readings)
                .containsExactly(
                        new Reading(Signal.API_P95, 184.2, Status.OK),
                        new Reading(Signal.SEARCH_P95, 92.0, Status.OK),
                        new Reading(Signal.KAFKA_LAG, 2400.0, Status.DEGRADED),
                        new Reading(Signal.STRIPE, 0.0, Status.OK),
                        Reading.unknown(Signal.TRACKING_STREAMS));
        PROM.verify(
                5,
                getRequestedFor(urlPathEqualTo("/prom/api/v1/query"))
                        .withHeader("Authorization", equalTo("Bearer fake-prometheus-token")));
    }

    @Test
    void aFailingStore_makesTheSignalsUnknown_notTheOverview() {
        PROM.stubFor(
                get(urlPathEqualTo("/prom/api/v1/query")).willReturn(aResponse().withStatus(503)));
        answer("max(lag)", """
                {"status":"error","errorType":"bad_data","error":"parse error"}""");

        assertThat(signals("").read())
                .extracting(Reading::status)
                .containsOnly(Status.UNKNOWN)
                .hasSize(5);
        PROM.verify(
                0,
                getRequestedFor(urlPathEqualTo("/prom/api/v1/query")).withHeader("Authorization", equalTo("Bearer ")));
    }

    @Test
    void noUrl_noCalls() {
        var none = new PrometheusHealthSignals(new ConsoleHealthProperties.Prometheus(
                "", "", Duration.ofSeconds(1), Map.of("api_p95", "up"), Map.of()));
        assertThat(none.read()).extracting(Reading::status).containsOnly(Status.UNKNOWN);
        PROM.verify(0, getRequestedFor(urlPathEqualTo("/prom/api/v1/query")));
    }
}
