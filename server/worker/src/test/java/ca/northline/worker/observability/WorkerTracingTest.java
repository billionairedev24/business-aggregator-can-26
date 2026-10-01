package ca.northline.worker.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.observation.ObservationRegistry;
import ca.northline.platform.logging.RedactionCheck;
import static ca.northline.worker.support.WorkerContainers.KAFKA;
import static ca.northline.worker.support.WorkerContainers.TEST_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.observability.OtlpReceiver;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.WorkerContainers;
import ca.northline.worker.support.WorkerIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-111, the Kafka hop: a record produced with the api's {@code traceparent} (what its Kafka template observation
 * writes — {@code TracingTest} in the api checks that side) is consumed in the same trace: the listener's CONSUMER
 * span and the dedupe claim's SQL span carry the producer's trace id, and the worker exports them over OTLP.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "LOG_FORMAT=ecs") // S-112: the deployed console format
class WorkerTracingTest extends WorkerIntegrationTest {

    static final OtlpReceiver OTLP = OtlpReceiver.start();

    @DynamicPropertySource
    static void otlp(DynamicPropertyRegistry registry) {
        OTLP.register(registry::add);
    }

    @Test
    void theConsumerContinuesTheProducersTrace() throws Exception {
        WorkerContainers.start();
        var trace = OtlpReceiver.newTraceId();
        var producerSpan = "b7ad6b7169203331";
        var id = Events.id();
        var record = Events.record(TEST_TOPIC, id, "payments.payout_failed", 1, Events.payoutFailed(id, "m_1"));
        record.headers()
                .add("traceparent", ("00-" + trace + "-" + producerSpan + "-01").getBytes(StandardCharsets.UTF_8));
        try (var producer = new KafkaProducer<String, byte[]>(Events.producerConfig(KAFKA.getBootstrapServers()))) {
            producer.send(record).get();
        }

        var consumer = OTLP.awaitSpans(
                        s -> s.traceId().equals(trace) && s.kind().equals("CONSUMER"), Duration.ofSeconds(30))
                .getFirst();
        assertThat(consumer.service()).isEqualTo("northline-worker");
        assertThat(consumer.parentSpanId()).isEqualTo(producerSpan);
        OTLP.awaitSpans(
                s -> s.traceId().equals(trace) && s.attributes().containsKey("jdbc.query[0]"), Duration.ofSeconds(30));
    }

    @Autowired
    ObservationRegistry observations;

    /** S-112: the console JSON line and the OTLP log record are redacted; the record carries its trace id. */
    @Test
    void logsLeaveRedactedAndLinkedToTheirTrace(CapturedOutput output) {
        RedactionCheck.assertRedacted("northline-worker", OTLP, observations, output::getOut);
    }
}
