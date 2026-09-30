package ca.northline.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.worker.events.EventSchemas;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Check 3 on made-up events: the divergences it must catch, and the samples it builds. */
class SamplePayloadsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    final SamplePayloads samples = new SamplePayloads(JSON);

    enum Outcome {
        FAILED,
        RETURNED
    }

    record PayoutFailed(
            String eventId,
            Instant occurredAt,
            String aggregateId,
            long amountCents,
            @Nullable String failureCode,
            Outcome outcome) {}

    static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"northline:testing.payout_failed:1",
             "type":"object","required":["eventId","occurredAt","aggregateId","amountCents","outcome"],
             "additionalProperties":false,
             "properties":{"eventId":{"type":"string","pattern":"^[0-9A-HJKMNP-TV-Z]{26}$"},
                           "occurredAt":{"type":"string","format":"date-time"},"aggregateId":{"type":"string"},
                           "amountCents":{"type":"integer","minimum":1},
                           "failureCode":{"type":["string","null"]},
                           "outcome":{"enum":["FAILED","RETURNED"]}}}""";

    @Test
    void aMatchingRecordValidates_withNullablesSetAndNull_andEveryEnumConstant() {
        assertThat(problems(PayoutFailed.class, SCHEMA)).isEmpty();
        assertThat(samples.samples(PayoutFailed.class, JSON.readTree(SCHEMA)))
                .extracting(SamplePayloads.Sample::label)
                .containsExactly("all fields set", "@Nullable fields null", "outcome = FAILED", "outcome = RETURNED");
        var first = samples.samples(PayoutFailed.class, JSON.readTree(SCHEMA))
                .getFirst()
                .payload();
        assertThat(first.path("eventId").asString()).isEqualTo("01J9ZD3V00000000000000EVT1");
        assertThat(first.path("amountCents").asLong()).isEqualTo(1);
        assertThat(first.path("occurredAt").asString()).isEqualTo("2026-09-30T18:00:00Z");
    }

    @Test
    void catchesWhatDiverges() {
        assertThat(problems(
                        PayoutFailed.class,
                        SCHEMA.replace(
                                "\"failureCode\":{\"type\":[\"string\",\"null\"]}",
                                "\"failureCode\":{\"type\":\"string\"}")))
                .containsExactly("[@Nullable fields null] $.failureCode must be \"string\"");
        assertThat(problems(
                        PayoutFailed.class,
                        SCHEMA.replace(
                                "\"outcome\":{\"enum\":[\"FAILED\",\"RETURNED\"]}",
                                "\"outcome\":{\"enum\":[\"FAILED\"]}")))
                .containsExactly("[outcome = RETURNED] $.outcome must be one of [\"FAILED\"]");
        assertThat(problems(
                        PayoutFailed.class,
                        SCHEMA.replace(
                                "\"amountCents\":{\"type\":\"integer\",\"minimum\":1}",
                                "\"amountCents\":{\"type\":\"string\"}")))
                .contains("[all fields set] $.amountCents must be \"string\"");
        assertThat(problems(PayoutFailed.class, SCHEMA.replace("\"aggregateId\":{\"type\":\"string\"},", "")))
                .contains("[all fields set] $.aggregateId is not allowed");
        assertThat(problems(
                        PayoutFailed.class,
                        SCHEMA.replace("\"required\":[\"eventId\",", "\"required\":[\"merchantId\",\"eventId\",")))
                .contains("[all fields set] $.merchantId is required");
    }

    record Odd(String eventId, Instant occurredAt, String aggregateId, Thread thread) {}

    @Test
    void aTypeItCantBuildIsReported_notSkipped() {
        assertThatThrownBy(() -> samples.samples(Odd.class, JSON.readTree(SCHEMA)))
                .isInstanceOf(SamplePayloads.SampleException.class)
                .hasMessageContaining("no sample for java.lang.Thread");
    }

    List<String> problems(Class<?> type, String schemaJson) {
        JsonNode schema = JSON.readTree(schemaJson);
        var validator = EventSchemas.of(Map.of("test", schema));
        return samples.samples(type, schema).stream()
                .flatMap(s -> validator.validate("testing.payout_failed", 1, s.payload()).orElseThrow().stream()
                        .map(p -> "[" + s.label() + "] " + p))
                .toList();
    }
}
