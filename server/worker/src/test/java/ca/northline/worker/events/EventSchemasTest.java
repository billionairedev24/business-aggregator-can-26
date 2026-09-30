package ca.northline.worker.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The subset validator: types, arrays of types, nested items, lengths. */
class EventSchemasTest {

    final JsonMapper json = JsonMapper.builder().build();
    final EventSchemas schemas = EventSchemas.fromClasspath(json);

    @Test
    void validatesArraysNullableFieldsAndPatterns() {
        var ok = json.readTree("""
                {"eventId":"01J9ZD3V00000000000000EVT1","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"sf_1",
                 "actorId":"u_1","merchantId":"m_1","slug":"prairie-wrench","pageKind":"store","sections":["hero","services"],
                 "customDomain":null}""");
        assertThat(schemas.validate("merchants.storefront_published", 1, ok)).hasValue(java.util.List.of());

        var bad = json.readTree("""
                {"eventId":"not-a-ulid","occurredAt":"2026-09-30T15:00:00Z","aggregateId":"sf_1",
                 "actorId":"u_1","merchantId":"m_1","slug":"Prairie Wrench!","sections":["hero",3],
                 "customDomain":7}""");
        assertThat(schemas.validate("merchants.storefront_published", 1, bad).orElseThrow())
                .anyMatch(p -> p.startsWith("$.eventId must match"))
                .anyMatch(p -> p.startsWith("$.slug must match"))
                .anyMatch(p -> p.startsWith("$.sections[1] must be"))
                .anyMatch(p -> p.startsWith("$.customDomain must be"));
    }

    @Test
    void unknownSchemaIsEmpty() {
        assertThat(schemas.validate("nope.nothing", 1, json.readTree("{}"))).isEmpty();
    }

    @Test
    void supportsExactlyTheKeywordsItImplements() {
        assertThat(EventSchemas.KEYWORDS).contains("type", "required", "properties", "enum", "pattern", "items");
    }
}
