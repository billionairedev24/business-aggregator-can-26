package ca.northline.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Check 4's rules on small before/after pairs. */
class BreakingChangesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BASE = """
            {"type":"object","required":["eventId","amountCents"],"additionalProperties":true,
             "properties":{"eventId":{"type":"string","pattern":"^[0-9A-Z]{26}$"},
                           "amountCents":{"type":"integer","minimum":0},
                           "caseNumber":{"type":["string","null"],"maxLength":20},
                           "outcome":{"enum":["failed","canceled"]},
                           "lines":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}}}}}}""";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            field removed            | /properties/caseNumber           | REMOVE                        | $.caseNumber: field removed
            newly required           | /required                        | ["eventId","amountCents","outcome"] | $.outcome: newly required
            nullable made non-null   | /properties/caseNumber/type      | "string"                      | $.caseNumber: type narrowed from [string, null] to [string] ([null] no longer allowed)
            number to integer        | /properties/amountCents/type     | "string"                      | $.amountCents: type narrowed from [integer] to [string] ([integer] no longer allowed)
            enum narrowed            | /properties/outcome/enum         | ["failed"]                    | $.outcome: enum narrowed, removed ["canceled"]
            object closed            | /additionalProperties            | false                         | $: additionalProperties set to false
            pattern changed          | /properties/eventId/pattern      | "^[0-9A-Z]{20}$"              | $.eventId: pattern changed from ^[0-9A-Z]{26}$ to ^[0-9A-Z]{20}$
            minimum raised           | /properties/amountCents/minimum  | 1                             | $.amountCents: minimum raised from 0 to 1
            maxLength lowered        | /properties/caseNumber/maxLength | 10                            | $.caseNumber: maxLength lowered from 20 to 10
            nested field removed     | /properties/lines/items/properties/sku | REMOVE                  | $.lines[].sku: field removed
            format added             | /properties/eventId/format       | "date-time"                   | $.eventId: format added (date-time)
            """)
    void breaking(String name, String pointer, String value, String expected) {
        assertThat(BreakingChanges.compare(node(BASE), change(BASE, pointer, value)))
                .containsExactly(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            optional field added    | /properties/respondBy            | {"type":"string","format":"date-time"}
            enum widened            | /properties/outcome/enum         | ["failed","canceled","returned"]
            type widened            | /properties/amountCents/type     | ["integer","null"]
            integer to number       | /properties/amountCents/type     | "number"
            minimum lowered         | /properties/amountCents/minimum  | -5
            maxLength raised        | /properties/caseNumber/maxLength | 40
            description added       | /properties/eventId/description  | "the event id"
            requirement dropped     | /required                        | ["eventId"]
            """)
    void notBreaking(String name, String pointer, String value) {
        assertThat(BreakingChanges.compare(node(BASE), change(BASE, pointer, value)))
                .isEmpty();
    }

    @Test
    void aDeletedFileIsBreaking_aNewVersionFileIsNot() {
        var v1 = node(BASE);
        assertThat(BreakingChanges.between(Map.of("payments.refund_issued.v1.schema.json", v1), Map.of()))
                .containsKey("payments.refund_issued.v1.schema.json");
        assertThat(BreakingChanges.between(
                        Map.of("payments.refund_issued.v1.schema.json", v1),
                        Map.of(
                                "payments.refund_issued.v1.schema.json",
                                v1,
                                "payments.refund_issued.v2.schema.json",
                                change(BASE, "/properties/caseNumber", "REMOVE"))))
                .isEmpty();
    }

    static JsonNode node(String json) {
        return JSON.readTree(json);
    }

    /** {@code base} with the node at {@code pointer} replaced by {@code value} (or removed). */
    static JsonNode change(String base, String pointer, String value) {
        var root = (tools.jackson.databind.node.ObjectNode) node(base);
        var parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
        var field = pointer.substring(pointer.lastIndexOf('/') + 1);
        var parent = (tools.jackson.databind.node.ObjectNode) (parentPointer.isEmpty() ? root : root.at(parentPointer));
        if (value.equals("REMOVE")) {
            parent.remove(field);
        } else {
            parent.set(field, node(value));
        }
        return root;
    }
}
