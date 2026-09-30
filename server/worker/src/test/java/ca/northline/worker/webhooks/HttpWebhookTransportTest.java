package ca.northline.worker.webhooks;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The SSRF-safe transport against a WireMock receiver: redirects, the response cap, timeouts, and every refusal —
 * which must happen before a single byte leaves (WireMock sees no request).
 */
class HttpWebhookTransportTest {

    static final WireMockServer RECEIVER = new WireMockServer(wireMockConfig().dynamicPort());
    static final byte[] BODY = "{\"id\":\"01J9ZD3V000000000000000EV1\"}".getBytes(StandardCharsets.UTF_8);

    /** Made-up names → chosen addresses (the system resolver is never asked). */
    static final HostResolver FAKE_DNS = host -> switch (host) {
        case "partner.example.test" -> List.of(InetAddress.getByName("127.0.0.1"));
        case "internal.example.test" -> List.of(InetAddress.getByName("10.0.0.7"));
        case "rebind.example.test" ->
            List.of(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254"));
        default -> List.of(InetAddress.getByName(host));
    };

    @BeforeAll
    static void start() {
        RECEIVER.start();
    }

    @AfterAll
    static void stop() {
        RECEIVER.stop();
    }

    @BeforeEach
    void reset() {
        RECEIVER.resetAll();
    }

    static WebhookProperties settings(boolean allowLocal) {
        return new WebhookProperties(
                null,
                allowLocal,
                4,
                20,
                Duration.ofMinutes(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                DataSize.ofKilobytes(64),
                200,
                new WebhookProperties.Retry(Duration.ofSeconds(30), 3, Duration.ofHours(12), 13, 0),
                Duration.ofDays(3),
                10,
                Duration.ofDays(30),
                "Northline-Webhooks/test");
    }

    static HttpWebhookTransport transport(boolean allowLocal) {
        return new HttpWebhookTransport(new EgressPolicy(allowLocal), FAKE_DNS, settings(allowLocal));
    }

    String local(String path) {
        return "http://localhost:" + RECEIVER.port() + path;
    }

    @Test
    void postsTheBody_andKeepsASnippetOfTheAnswerWithoutControlCharacters() throws Exception {
        RECEIVER.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withBody("ok\u0000\u0007received")));
        try (var transport = transport(true)) {
            var result = transport.post(URI.create(local("/hook")), Map.of("Northline-Event-Id", "EV1"), BODY);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.snippet()).isEqualTo("ok  received");
            assertThat(result.error()).isNull();
        }
        RECEIVER.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("Northline-Event-Id", equalTo("EV1"))
                .withHeader("Content-Type", equalTo("application/json; charset=UTF-8"))
                .withHeader("User-Agent", equalTo("Northline-Webhooks/test")));
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        RECEIVER.stubFor(
                post("/hook").willReturn(aResponse().withStatus(302).withHeader("Location", local("/elsewhere"))));
        RECEIVER.stubFor(get("/elsewhere").willReturn(aResponse().withStatus(200)));
        try (var transport = transport(true)) {
            var result = transport.post(URI.create(local("/hook")), Map.of(), BODY);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo(302);
            assertThat(result.outcome()).isEqualTo("HTTP 302");
        }
        RECEIVER.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
        RECEIVER.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    void aHugeAnswerIsCutAtTheCap() throws Exception {
        RECEIVER.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withBody("x".repeat(4 * 1024 * 1024))));
        try (var transport = transport(true)) {
            var result = transport.post(URI.create(local("/hook")), Map.of(), BODY);

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.snippet()).hasSize(200);
        }
    }

    @Test
    void aSlowEndpointTimesOut() throws Exception {
        RECEIVER.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withFixedDelay(2_500)));
        try (var transport = transport(true)) {
            var result = transport.post(URI.create(local("/hook")), Map.of(), BODY);

            assertThat(result.status()).isNull();
            assertThat(result.error()).startsWith("timed out");
            assertThat(result.durationMs()).isLessThan(2_500);
        }
    }

    @Test
    void outsideLocalDevelopment_httpIsRefusedBeforeConnecting() throws Exception {
        try (var transport = transport(false)) {
            var result = transport.post(URI.create(local("/hook")), Map.of(), BODY);

            assertThat(result.error()).isEqualTo("refused: only https:// URLs are allowed");
        }
        RECEIVER.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void loopbackMetadataAndPrivateAddressesAreRefusedAtResolution() throws Exception {
        try (var transport = transport(false)) {
            assertThat(transport
                            .post(URI.create("https://127.0.0.1:" + RECEIVER.port() + "/hook"), Map.of(), BODY)
                            .error())
                    .isEqualTo("refused: 127.0.0.1 resolves to 127.0.0.1 (loopback)");
            assertThat(transport
                            .post(URI.create("https://169.254.169.254/latest/meta-data/"), Map.of(), BODY)
                            .error())
                    .isEqualTo("refused: 169.254.169.254 resolves to 169.254.169.254 (link-local / cloud metadata)");
            assertThat(transport
                            .post(URI.create("https://[fd00:ec2::254]/"), Map.of(), BODY)
                            .error())
                    .startsWith("refused: ")
                    .contains("private network (unique local)");
            assertThat(transport
                            .post(URI.create("https://internal.example.test/hook"), Map.of(), BODY)
                            .error())
                    .isEqualTo("refused: internal.example.test resolves to 10.0.0.7 (private network)");
            assertThat(transport
                            .post(URI.create("https://rebind.example.test/hook"), Map.of(), BODY)
                            .error())
                    .isEqualTo(
                            "refused: rebind.example.test resolves to 169.254.169.254 (link-local / cloud metadata)");
        }
        RECEIVER.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void theConnectionGoesToTheCheckedAddress_theHostNameStaysInTheRequest() throws Exception {
        RECEIVER.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
        try (var transport = transport(true)) {
            var result = transport.post(
                    URI.create("http://partner.example.test:" + RECEIVER.port() + "/hook"), Map.of(), BODY);

            assertThat(result.status()).isEqualTo(204);
        }
        RECEIVER.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("Host", equalTo("partner.example.test:" + RECEIVER.port())));
    }

    @Test
    void anUnknownHostIsAFailedAttempt() throws Exception {
        HostResolver none = host -> {
            throw new UnknownHostException(host);
        };
        try (var transport = new HttpWebhookTransport(new EgressPolicy(false), none, settings(false))) {
            assertThat(transport
                            .post(URI.create("https://nowhere.example.test/"), Map.of(), BODY)
                            .error())
                    .isEqualTo("host not found");
        }
    }
}
