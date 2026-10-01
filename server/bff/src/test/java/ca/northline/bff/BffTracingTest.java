package ca.northline.bff;

import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.observation.ObservationRegistry;
import ca.northline.platform.logging.RedactionCheck;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.platform.observability.OtlpReceiver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S-111, the browser → BFF → api hop: the browser starts the trace ({@code traceparent} from {@code @northline/client}),
 * the BFF continues it in its SERVER span, relays the api call in a CLIENT span of the same trace and sends the api a
 * {@code traceparent} whose parent is that CLIENT span. The browser's "sampled" flag is not obeyed as such
 * ({@code ConsistentSampling}); here every trace is kept (ratio 1.0).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "consumer"})
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "LOG_FORMAT=ecs") // S-112: the deployed console format
class BffTracingTest {

    static final OtlpReceiver OTLP = OtlpReceiver.start();
    static final WireMockServer API =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        API.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("northline.bff.api-uri", API::baseUrl);
        OTLP.register(registry::add);
    }

    @AfterAll
    static void stop() {
        API.stop();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theBrowsersTraceReachesTheApiThroughTheRelay() throws Exception {
        API.stubFor(WireMock.any(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[]}")));
        var trace = OtlpReceiver.newTraceId();
        var session = new MockHttpSession();
        mvc.perform(get("/bff/session").session(session));

        mvc.perform(get("/api/v1/search")
                        .param("q", "bannock")
                        .session(session)
                        .header("traceparent", "00-" + trace + "-a3ce929d0e0e4736-00"))
                .andExpect(status().isOk());

        var sent = API.findAll(getRequestedFor(urlPathEqualTo("/api/v1/search")));
        assertThat(sent).hasSize(1);
        var traceparent = sent.getFirst().getHeader("traceparent");
        assertThat(traceparent).startsWith("00-" + trace + "-").endsWith("-01");

        var server = OTLP.awaitSpans(
                        s -> s.traceId().equals(trace) && s.kind().equals("SERVER"), Duration.ofSeconds(20))
                .getFirst();
        assertThat(server.service()).isEqualTo("northline-consumer-bff");
        assertThat(server.parentSpanId()).isEqualTo("a3ce929d0e0e4736");
        var client = OTLP.awaitSpans(
                        s -> s.traceId().equals(trace) && s.kind().equals("CLIENT"), Duration.ofSeconds(20))
                .getFirst();
        assertThat(traceparent).contains("-" + client.spanId() + "-");
    }

    @Autowired
    ObservationRegistry observations;

    /** S-112: the console JSON line and the OTLP log record are redacted; the record carries its trace id. */
    @Test
    void logsLeaveRedactedAndLinkedToTheirTrace(CapturedOutput output) {
        RedactionCheck.assertRedacted("northline-consumer-bff", OTLP, observations, output::getOut);
    }
}
