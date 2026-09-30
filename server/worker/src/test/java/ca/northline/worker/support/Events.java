package ca.northline.worker.support;

import ca.northline.worker.events.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;

/** Records shaped like the api's externalized events (key = aggregate id, JSON value, nl-event-* headers). */
public final class Events {

    private static int counter;

    private Events() {}

    /** A fresh 26-character ULID-shaped id (Crockford alphabet). */
    public static synchronized String id() {
        var base = Long.toString(System.nanoTime() + counter++, 32).toUpperCase(java.util.Locale.ROOT);
        var padded = ("01J9ZD3V" + "0".repeat(26) + base).replaceAll("[ILOU]", "Z");
        return padded.substring(padded.length() - 26);
    }

    public static String payoutFailed(String eventId, String merchantId) {
        return """
                {"eventId":"%s","occurredAt":"%s","aggregateId":"po_%s","merchantId":"%s","outcome":"failed",\
                "amountCents":81437,"failureCode":"account_closed"}""".formatted(eventId, Instant.parse("2026-09-30T15:00:00Z"), eventId, merchantId);
    }

    public static ProducerRecord<String, byte[]> record(
            String topic, String eventId, String type, int version, String json) {
        var headers = new RecordHeaders();
        headers.add(EventHeaders.ID, eventId.getBytes(StandardCharsets.UTF_8));
        headers.add(EventHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        headers.add(EventHeaders.VERSION, Integer.toString(version).getBytes(StandardCharsets.UTF_8));
        headers.add(
                EventHeaders.TRACEPARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8));
        return new ProducerRecord<>(topic, null, "agg-" + eventId, json.getBytes(StandardCharsets.UTF_8), headers);
    }

    public static ConsumerRecord<String, byte[]> consumed(ProducerRecord<String, byte[]> record) {
        var consumed = new ConsumerRecord<>(record.topic(), 0, 42L, record.key(), record.value());
        record.headers().forEach(h -> consumed.headers().add(h));
        return consumed;
    }

    public static Map<String, Object> producerConfig(String bootstrap) {
        return Map.of(
                "bootstrap.servers", bootstrap,
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
    }
}
