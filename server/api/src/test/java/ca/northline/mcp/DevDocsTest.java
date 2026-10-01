package ca.northline.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S-128: how {@link DevDocs} splits, searches and resolves (no Spring). */
class DevDocsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String RUNBOOK = """
            # Payments runbook

            Intro about money.

            ## Webhook secrets

            Set STRIPE_WEBHOOK_SECRET in staging. The webhook secret rotates yearly.

            ```sh
            # not a heading inside a fence
            echo webhook
            ```

            ## Refunds

            Refunds are manual.
            """;

    private static final String SPEC = """
            openapi: 3.1.0
            paths:
              /api/v1/things/{id}:
                parameters:
                  - $ref: "#/components/parameters/Id"
                patch:
                  operationId: thingUpdate
                  summary: Change a thing
                  tags: [things]
                  requestBody:
                    content:
                      application/json:
                        schema: { $ref: "#/components/schemas/Thing" }
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema: { $ref: "#/components/schemas/Thing" }
            components:
              parameters:
                Id: { in: path, name: id, required: true, schema: { type: string } }
              schemas:
                Thing:
                  type: object
                  properties:
                    name: { type: string }
                    parent: { $ref: "#/components/schemas/Thing" }
            """;

    private final DevDocs docs = DevDocs.of(
            Map.of("runbooks/payments.md", RUNBOOK, "api/openapi/api-test.yaml", SPEC, "README.md", "No heading."),
            JSON);

    @Test
    void documentsAreSplitIntoSections_fencesAreNotHeadings() {
        var doc = docs.document("docs/runbooks/payments.md").orElseThrow();

        assertThat(doc.title()).isEqualTo("Payments runbook");
        assertThat(doc.sections())
                .extracting(DevDocs.Section::heading)
                .containsExactly("Payments runbook", "Webhook secrets", "Refunds");
        assertThat(doc.sections().get(1).anchor()).isEqualTo("webhook-secrets");
        assertThat(doc.sections().get(1).text()).contains("not a heading inside a fence");
        assertThat(docs.document("README.md").orElseThrow().title()).isEqualTo("README.md");
    }

    @Test
    void search_needsEveryWord_ranksHeadingsHigher() {
        var hits = docs.search("webhook secret", 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().heading()).isEqualTo("Webhook secrets");
        assertThat(hits.getFirst().snippet()).containsIgnoringCase("webhook");
        assertThat(docs.search("refunds", 5).getFirst().anchor()).isEqualTo("refunds");
        assertThat(docs.search("webhook bitcoin", 5)).isEmpty();
        assertThat(docs.search("  ", 5)).isEmpty();
    }

    @Test
    void operations_areListedAndResolved_recursiveSchemasKeepTheirRef() {
        assertThat(docs.operations(null, "thing"))
                .singleElement()
                .satisfies(op -> {
                    assertThat(op.method()).isEqualTo("PATCH");
                    assertThat(op.spec()).isEqualTo("api-test");
                    assertThat(op.tags()).containsExactly("things");
                });

        var byId = docs.operation(null, "thingUpdate", null, null).orElseThrow();
        var byPath = docs.operation("api-test", null, "patch", "/api/v1/things/{id}")
                .orElseThrow();
        assertThat(byPath).isEqualTo(byId);

        var parameters = (JsonNode) byId.get("parameters");
        assertThat(parameters.get(0).get("name").asString()).isEqualTo("id");
        var schema = ((JsonNode) byId.get("requestBody")).at("/content/application~1json/schema");
        assertThat(schema.at("/properties/name/type").asString()).isEqualTo("string");
        assertThat(schema.at("/properties/parent/$ref").asString()).isEqualTo("#/components/schemas/Thing");
        assertThat(docs.operation(null, "nope", null, null)).isEmpty();
    }
}
