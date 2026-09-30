package ca.northline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.platform.observability.OtlpReceiver;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * S-111: the api exports what it observes over OTLP (here to {@link OtlpReceiver}, a stand-in Collector), continues the
 * trace the BFF (or a browser) started with {@code traceparent}, traces its SQL, and hands the trace on to Kafka: the
 * externalized event's {@code traceparent} carries the same trace id — the hop the worker continues
 * ({@code WorkerTracingTest}).
 */
class TracingTest extends IntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static final OtlpReceiver OTLP = OtlpReceiver.start();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.modulith.events.externalization.enabled", () -> "true");
        OTLP.register(registry::add);
    }

    @BeforeAll
    static void topic() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("merchants.merchant", 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    @Test
    void continuesTheCallersTraceThroughSqlAndIntoKafka() throws Exception {
        var trace = OtlpReceiver.newTraceId();
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .header("traceparent", "00-" + trace + "-00f067aa0ba902b7-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Prairie Wrench Traced\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());

        var server = OTLP.awaitSpans(
                        s -> s.traceId().equals(trace) && s.kind().equals("SERVER"), Duration.ofSeconds(20))
                .getFirst();
        assertThat(server.service()).isEqualTo("northline-api");
        assertThat(server.parentSpanId()).isEqualTo("00f067aa0ba902b7");

        // DB: one span per statement, the SQL without its parameter values.
        var sql = OTLP.awaitSpans(
                s -> s.traceId().equals(trace) && s.attributes().containsKey("jdbc.query[0]"), Duration.ofSeconds(20));
        assertThat(sql).allSatisfy(s -> assertThat(s.attributes()).noneSatisfy((k, _) -> assertThat(k)
                .startsWith("jdbc.params")));
        assertThat(sql).noneSatisfy(s -> assertThat(s.attributes().values()).contains("Prairie Wrench Traced"));

        // Kafka: the outbox externalizer produces with the request's trace.
        var record = poll(biz.merchantId());
        var traceparent = record.headers().lastHeader("traceparent");
        assertThat(traceparent).isNotNull();
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).startsWith("00-" + trace + "-");
        OTLP.awaitSpans(s -> s.traceId().equals(trace) && s.kind().equals("PRODUCER"), Duration.ofSeconds(20));
    }

    @Test
    void exportsMetricsWithTheHttpHistograms() throws Exception {
        mvc.perform(get("/api/v1/me/businesses").with(TestJwt.customer("01J9ZD3V0000000000000TRACE")))
                .andExpect(status().isOk());

        var metrics = OTLP.awaitMetrics(
                m -> m.service().equals("northline-api") && m.name().equals("http.server.requests"),
                Duration.ofSeconds(20));
        assertThat(metrics.getFirst().unit()).isEqualTo("s");
    }

    @Test
    void probesAreNotTraced() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        Thread.sleep(500);
        assertThat(OTLP.spans()).noneSatisfy(s -> assertThat(s.name()).contains("actuator"));
    }

    private static ConsumerRecord<String, byte[]> poll(String key) {
        try (var consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "tracing-test",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("merchants.merchant"));
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("no record for " + key);
        }
    }
}
