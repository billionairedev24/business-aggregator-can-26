package ca.northline.worker.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.worker.support.Events;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Envelope headers + JSON payload + versioned schema → EventEnvelope, or a poison record. */
class EnvelopeParserTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final EventSchemas SCHEMAS = EventSchemas.fromClasspath(JSON);
    final EnvelopeParser parser = new EnvelopeParser(JSON, SCHEMAS);

    @Test
    void loadsEverySchemaTheApiPublishes() throws Exception {
        try (var files =
                Files.list(Path.of(System.getProperty("northline.repo"), "server/api/src/main/resources/events"))) {
            assertThat(SCHEMAS.keys()).hasSize((int) files.count());
        }
        assertThat(SCHEMAS.knows("payments.payout_failed", 1)).isTrue();
        assertThat(SCHEMAS.knows("booking.quote_accepted", 2)).isTrue();
        assertThat(SCHEMAS.knows("booking.quote_accepted", 1)).isFalse();
    }

    @Test
    void parsesAValidEvent() {
        var id = Events.id();
        var event = parser.parse(Events.consumed(
                Events.record("payments.payout", id, "payments.payout_failed", 1, Events.payoutFailed(id, "m_1"))));
        assertThat(event.id()).isEqualTo(id);
        assertThat(event.type()).isEqualTo("payments.payout_failed");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(event.aggregateId()).isEqualTo("po_" + id);
        assertThat(event.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(event.text("merchantId")).isEqualTo("m_1");
        assertThat(event.optionalText("failureCode")).isEqualTo("account_closed");
        assertThat(event.topic()).isEqualTo("payments.payout");
    }

    @Test
    void poisonRecords() {
        var id = Events.id();
        var valid = Events.payoutFailed(id, "m_1");
        assertPoison(record(id, "payments.payout_failed", 1, "{not json"), "not JSON");
        assertPoison(record(id, "payments.payout_failed", 9, valid), "no schema for payments.payout_failed v9");
        assertPoison(record(id, "payments.nope", 1, valid), "no schema for payments.nope v1");
        assertPoison(record(Events.id(), "payments.payout_failed", 1, valid), "differs from the payload's eventId");
        assertPoison(
                record(id, "payments.payout_failed", 1, valid.replace("\"failed\"", "\"lost\"")),
                "$.outcome must be one of");
        assertPoison(
                record(id, "payments.payout_failed", 1, valid.replace(",\"merchantId\":\"m_1\"", "")),
                "$.merchantId is required");
        assertPoison(record(id, "payments.payout_failed", 1, valid.replace("81437", "0")), "$.amountCents must be ≥ 1");
        assertPoison(
                record(id, "payments.payout_failed", 1, valid.replace("81437", "\"81437\"")),
                "$.amountCents must be \"integer\"");
        assertPoison(
                record(id, "payments.payout_failed", 1, valid.replace("2026-09-30T15:00:00Z", "yesterday")),
                "$.occurredAt must be an RFC 3339 date-time");
        var noHeaders = new ConsumerRecord<String, byte[]>(
                "payments.payout", 0, 0, "k", valid.getBytes(StandardCharsets.UTF_8));
        assertPoison(noHeaders, "no nl-event-id header");
    }

    private static ConsumerRecord<String, byte[]> record(String id, String type, int version, String json) {
        return Events.consumed(Events.record("payments.payout", id, type, version, json));
    }

    private void assertPoison(ConsumerRecord<String, byte[]> record, String message) {
        assertThatThrownBy(() -> parser.parse(record))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining(message);
    }
}
