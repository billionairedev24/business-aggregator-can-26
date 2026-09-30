package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.platform.EventHeaders;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-26: what the worker receives, from the real Kafka producer path (outbox → Modulith externalizer → KafkaTemplate →
 * Kafka 4): key = aggregate id, value = the event's JSON, headers {@code nl-event-id|type|version}.
 */
class EventsOnKafkaTest extends IntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.modulith.events.externalization.enabled", () -> "true");
    }

    @Autowired
    JsonMapper json;

    @Test
    void externalizedEventCarriesTheEnvelopeHeadersAndItsJson() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("merchants.merchant", 1, (short) 1)))
                    .all()
                    .get();
        }
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Prairie Wrench Mobile\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());

        var record = poll("merchants.merchant", biz.merchantId());
        assertThat(record.key()).isEqualTo(biz.merchantId());
        var body = json.readTree(record.value());
        assertThat(header(record, EventHeaders.ID))
                .isEqualTo(body.get("eventId").asString());
        assertThat(header(record, EventHeaders.TYPE)).isEqualTo("merchants.merchant_renamed");
        assertThat(header(record, EventHeaders.VERSION)).isEqualTo("1");
        assertThat(body.get("aggregateId").asString()).isEqualTo(biz.merchantId());
        assertThat(body.has("occurredAt")).isTrue();
        assertThat(getClass().getClassLoader().getResource("events/merchants.merchant_renamed.v1.schema.json"))
                .isNotNull();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as(name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, byte[]> poll(String topic, String key) {
        try (var consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "events-on-kafka-test",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            var seen = new ArrayList<String>();
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                    seen.add(record.key());
                }
            }
            throw new AssertionError("no record for " + key + " on " + topic + " (saw " + seen + ")");
        }
    }
}
