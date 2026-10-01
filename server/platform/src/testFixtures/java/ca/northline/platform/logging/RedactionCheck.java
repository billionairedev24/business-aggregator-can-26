package ca.northline.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.observability.OtlpReceiver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * S-112 check shared by every app's tests: inside a span, the app logs a line full of personal data and secrets; the
 * console line (ECS JSON, {@code LOG_FORMAT=ecs}) and the OTLP log record must both be redacted, and the OTLP record
 * must carry the span's trace id.
 *
 * <pre>{@code
 * @SpringBootTest(properties = "LOG_FORMAT=ecs") … OtlpReceiver.register(…)
 * RedactionCheck.assertRedacted("northline-api", OTLP, observations, output::getOut);
 * }</pre>
 */
public final class RedactionCheck {

    /** What a careless log line could carry; none of the sensitive parts may come out. */
    public static final String LINE = "Sent to amara.osei@example.ca, mobile +1 587 555 0101, card 4242 4242 4242 4242,"
            + " postal T2P 1B5, verification code 482913, Authorization: Bearer abcdefgh12345678";

    private static final String[] NEVER = {"amara.osei", "555 0101", "4242 4242", "1B5", "482913", "abcdefgh12345678"};

    private RedactionCheck() {}

    public static void assertRedacted(
            String service, OtlpReceiver otlp, ObservationRegistry observations, Supplier<String> console) {
        var marker = "redaction-check-" + System.nanoTime();
        var log = LoggerFactory.getLogger("ca.northline.redaction");
        Observation.createNotStarted(marker, observations).observe(() -> log.warn("{} {}", marker, LINE));

        var span = otlp.awaitSpans(s -> s.name().equals(marker), Duration.ofSeconds(20))
                .getFirst();
        var record = otlp.awaitLogs(l -> l.body().startsWith(marker), Duration.ofSeconds(20))
                .getFirst();
        assertThat(record.service()).isEqualTo(service);
        assertThat(record.traceId()).isEqualTo(span.traceId());
        assertThat(record.body())
                .contains("[EMAIL]", "[PHONE]", "[CARD …4242]", "T2P ***", "[CODE]", "[REDACTED]")
                .doesNotContain(NEVER);

        var line = console.get()
                .lines()
                .filter(l -> l.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no console line with " + marker));
        assertThat(line)
                .startsWith("{")
                .contains("\"trace.id\":\"" + span.traceId() + "\"")
                .doesNotContain(NEVER);
    }
}
