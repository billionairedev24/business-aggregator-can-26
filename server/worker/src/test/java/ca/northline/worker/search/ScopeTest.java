package ca.northline.worker.search;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.worker.events.EventEnvelope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What an event makes the projection re-read. */
class ScopeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static EventEnvelope event(String type, String json) {
        var data = JSON.readTree(json);
        return new EventEnvelope(
                data.path("eventId").asString(),
                type,
                1,
                Instant.parse(data.path("occurredAt").asString()),
                data.path("aggregateId").asString(),
                null,
                "merchants.merchant",
                data);
    }

    @Test
    void anErasureTouchingABusiness_rereadsThatBusiness() {
        // S-105: published on the business's own topic; ids only (the privacy request, never whose data)
        var scope = Scope.of(event("privacy.merchant_data_erased", """
                {"eventId": "01JA0000000000000000000001", "occurredAt": "2026-10-02T12:00:00Z",
                 "aggregateId": "01J9ZD3V00000000000000PWM1", "requestId": "01JA0000000000000000000002"}
                """));

        assertThat(scope).isEqualTo(new Scope.Merchant("01J9ZD3V00000000000000PWM1"));
    }
}
