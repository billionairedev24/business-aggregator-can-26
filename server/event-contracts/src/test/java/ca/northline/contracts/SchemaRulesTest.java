package ca.northline.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Check 1: the subset of JSON Schema the worker implements, and the repository's conventions. */
class SchemaRulesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String GOOD = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"northline:payments.payout_failed:1",
             "type":"object","required":["eventId","occurredAt","aggregateId"],"additionalProperties":true,
             "properties":{"eventId":{"type":"string","pattern":"^[0-9A-HJKMNP-TV-Z]{26}$"},
                           "occurredAt":{"type":"string","format":"date-time"},"aggregateId":{"type":"string"},
                           "day":{"type":"string","format":"date"}}}""";

    @Test
    void aConformingSchemaHasNoProblems() {
        assertThat(SchemaRules.problems("payments.payout_failed.v1.schema.json", JSON.readTree(GOOD)))
                .isEmpty();
    }

    @Test
    void everyRuleNamesTheProblem() {
        var bad = GOOD.replace(
                        "\"$id\":\"northline:payments.payout_failed:1\"", "\"$id\":\"northline:payments.payout:1\"")
                .replace("\"additionalProperties\":true", "\"additionalProperties\":{\"type\":\"string\"},\"oneOf\":[]")
                .replace("\"format\":\"date\"", "\"format\":\"email\"")
                .replace("\"pattern\":\"^[0-9A-HJKMNP-TV-Z]{26}$\"", "\"pattern\":\"[\"")
                .replace(
                        "\"required\":[\"eventId\",\"occurredAt\",\"aggregateId\"]",
                        "\"required\":[\"eventId\",\"occurredAt\",\"merchantId\"]");

        assertThat(SchemaRules.problems("payments.payout_failed.v1.schema.json", JSON.readTree(bad)))
                .contains(
                        "$id must be northline:payments.payout_failed:1 (was \"northline:payments.payout:1\")",
                        "the envelope field aggregateId must be required",
                        "$.required names merchantId, which properties doesn't declare",
                        "$.additionalProperties must be true or false (schemas there are not implemented)",
                        "$.day.format \"email\" isn't implemented by the worker (only "
                                + ca.northline.worker.events.EventSchemas.FORMATS + ")")
                .anyMatch(p -> p.startsWith("uses oneOf, which the worker's EventSchemas doesn't implement"))
                .anyMatch(p -> p.startsWith("$.eventId.pattern is not a valid regular expression"));
        assertThat(SchemaRules.problems("PayoutFailed.json", JSON.readTree(GOOD)))
                .contains("file name must be <module>.<event>.v<version>.schema.json");
    }
}
