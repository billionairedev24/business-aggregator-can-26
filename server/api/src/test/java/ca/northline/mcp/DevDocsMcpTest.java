package ca.northline.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.MerchantRole;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-128: the developer docs MCP server at {@code /mcp/docs}, as a coding agent uses it locally (no token): discovery,
 * the read-only tools over the packaged docs and OpenAPI documents, the resources; and its tokens confined to it.
 * Staff-only access (the cloud) is {@link DevDocsAccessFilterTest}.
 */
class DevDocsMcpTest extends McpTestServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void metadataNamesTheDocsServer() throws Exception {
        var metadata = http("GET", "/.well-known/oauth-protected-resource/mcp/docs", null, null, Map.of());

        assertThat(metadata.statusCode()).isEqualTo(200);
        var json = JSON.readTree(metadata.body());
        assertThat(json.get("resource").asString()).isEqualTo(DOCS_RESOURCE);
        assertThat(json.get("authorization_servers").get(0).asString()).isEqualTo(ISSUER);
        assertThat(json.get("scopes_supported").toString()).contains("\"mcp\"").doesNotContain("mcp.write");
    }

    @Test
    void anAgentSearchesReadsAndLooksUpAnOperation() {
        try (var docs = client(null, "/mcp/docs")) {
            docs.initialize();

            var tools = docs.listTools().tools();
            assertThat(tools)
                    .extracting(Tool::name)
                    .containsExactlyInAnyOrder(
                            "search_docs", "list_documents", "get_document", "list_operations", "get_operation");
            assertThat(tools).allSatisfy(t -> assertThat(t.annotations().readOnlyHint())
                    .isTrue());

            var hits = json(docs.callTool(new CallToolRequest("search_docs", Map.of("query", "confirmation_required"))));
            assertThat(hits.get("hits").findValuesAsString("path")).contains("runbooks/mcp.md");

            var section = json(docs.callTool(new CallToolRequest(
                    "get_document", Map.of("path", "runbooks/mcp.md", "section", "Confirmations"))));
            assertThat(section.get("text").asString()).contains("already_done");

            var page = json(docs.callTool(new CallToolRequest("get_document", Map.of("path", "DECISIONS.md"))));
            assertThat(page.get("nextOffset").asInt()).isPositive();

            var operations = json(docs.callTool(new CallToolRequest(
                    "list_operations", Map.of("spec", "api-studio", "query", "price-stock"))));
            assertThat(operations.get("operations").findValuesAsString("operationId"))
                    .containsExactly("listingUpdatePriceAndStock");

            var operation = json(docs.callTool(
                    new CallToolRequest("get_operation", Map.of("operationId", "listingUpdatePriceAndStock"))));
            assertThat(operation.get("method").asString()).isEqualTo("PATCH");
            // the request schema is inlined, not a $ref the agent would have to chase
            var body = operation.at("/requestBody/content/application~1json/schema");
            assertThat(body.has("$ref")).isFalse();
            assertThat(body.path("properties").has("priceCents")).isTrue();
            assertThat(operation.at("/responses/422").isMissingNode()).isFalse();
        }
    }

    @Test
    void documentsAndSpecsAreResources() {
        try (var docs = client(null, "/mcp/docs")) {
            docs.initialize();

            var uris = docs.listResources().resources().stream().map(Resource::uri).toList();
            assertThat(uris)
                    .contains(
                            "northline-docs://docs/runbooks/mcp.md",
                            "northline-docs://docs/ARCHITECTURE.md",
                            "northline-docs://openapi/api-studio.yaml");

            var spec = docs.readResource(new ReadResourceRequest("northline-docs://openapi/api-public.yaml"));
            assertThat(((TextResourceContents) spec.contents().getFirst()).text()).startsWith("openapi:");
        }
    }

    @Test
    void unknownDocument_isAToolError() {
        try (var docs = client(null, "/mcp/docs")) {
            docs.initialize();

            var result = docs.callTool(new CallToolRequest("get_document", Map.of("path", "runbooks/nope.md")));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("No document runbooks/nope.md");
        }
    }

    @Test
    void docsTokens_workNowhereElse() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var docsToken = token(
                biz.userId(),
                "openid mcp",
                "mfa",
                List.of("northline-mcp", "northline-api", DOCS_RESOURCE),
                "northline-mcp",
                biz.merchantId());

        var api = http("GET", "/api/v1/me/businesses", docsToken, null, Map.of());
        assertThat(api.statusCode()).isEqualTo(403);
        assertThat(api.body()).contains("mcp_token");

        var business = http(
                "POST",
                "/mcp",
                docsToken,
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                Map.of("Accept", "application/json, text/event-stream"));
        assertThat(business.statusCode()).isEqualTo(401);
    }

    private static JsonNode json(CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isFalse();
        return JSON.readTree(text(result));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
