package ca.northline.auth.observability;

import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.observation.ObservationRegistry;
import ca.northline.platform.logging.RedactionCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.platform.observability.OtlpReceiver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-111 on northline-auth: requests are traced (continuing the caller's {@code traceparent}) with their SQL, exported
 * over OTLP, and sign-ins are counted by method, second factor and outcome ({@code northline.auth.sign_ins}) — never
 * with who signed in.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "LOG_FORMAT=ecs") // S-112: the deployed console format
class AuthTelemetryTest extends AuthIntegrationTest {

    static final OtlpReceiver OTLP = OtlpReceiver.start();

    @DynamicPropertySource
    static void otlp(DynamicPropertyRegistry registry) {
        OTLP.register(registry::add);
    }

    @Autowired
    MeterRegistry meters;

    @Test
    void aSignInIsTracedAndCounted() throws Exception {
        var user = register(newPerson());
        var before = count("succeeded");
        var trace = OtlpReceiver.newTraceId();
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/sign-in")
                        .session(session)
                        .header("traceparent", "00-" + trace + "-1111111111111111-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("identifier", user.person().email()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/sign-in/totp")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(user.totpSecret())))))
                .andExpect(status().isOk());

        assertThat(count("succeeded")).isEqualTo(before + 1);
        var server = OTLP.awaitSpans(
                        s -> s.traceId().equals(trace) && s.kind().equals("SERVER"), Duration.ofSeconds(20))
                .getFirst();
        assertThat(server.service()).isEqualTo("northline-auth");
        var sql = OTLP.awaitSpans(
                s -> s.traceId().equals(trace) && s.attributes().containsKey("jdbc.query[0]"), Duration.ofSeconds(20));
        assertThat(sql)
                .noneSatisfy(s -> assertThat(String.join(" ", s.attributes().values()))
                        .contains(user.person().email()));
        OTLP.awaitMetrics(
                m -> m.service().equals("northline-auth") && m.name().equals("northline.auth.sign_ins"),
                Duration.ofSeconds(20));
    }

    @Test
    void aWrongCodeIsCountedAsAFailure() throws Exception {
        var user = register(newPerson());
        var before = count("failed");
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/sign-in")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("identifier", user.person().email()))));
        mvc.perform(post("/api/auth/sign-in/totp")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("code", "000000"))));

        assertThat(count("failed")).isGreaterThan(before);
    }

    private double count(String outcome) {
        return meters.find("northline.auth.sign_ins").tag("outcome", outcome).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    @Autowired
    ObservationRegistry observations;

    /** S-112: the console JSON line and the OTLP log record are redacted; the record carries its trace id. */
    @Test
    void logsLeaveRedactedAndLinkedToTheirTrace(CapturedOutput output) {
        RedactionCheck.assertRedacted("northline-auth", OTLP, observations, output::getOut);
    }
}
