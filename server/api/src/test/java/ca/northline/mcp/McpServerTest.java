package ca.northline.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.MerchantRole;
import ca.northline.tools.CategorySeeder;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-127 end to end with the MCP Java SDK's client over Streamable HTTP: discovery (RFC 9728) and the 401 challenge,
 * then a session — initialize, list tools, a read, a write that needs its confirming second call and is applied once
 * — resources, and every refusal: no second factor, a token for another audience, a missing scope, another business,
 * the agent's token used on the REST api directly, staff tools. Every tool call leaves an audit row.
 */
class McpServerTest extends McpTestServer {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<String, String> MCP_HEADERS =
            Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2025-06-18");
    static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
            "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";
    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seedCategoriesOnce() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
    }

    /** A service listing created through the REST api with a Studio token (not an agent's). */
    String service(String merchantId, String userId) throws Exception {
        var studio = token(
                userId,
                "openid profile merchant",
                "mfa",
                List.of("studio-bff", "northline-api"),
                "studio-bff",
                merchantId);
        var created = http("POST", "/api/v1/merchants/" + merchantId + "/services", studio, """
                {"name":"Tire swap","categoryId":"service.automotive.tire-change-and-storage","pricingMode":"fixed",
                 "priceCents":9900,"durationMin":60,"bufferMin":15,"included":"Swap four mounted wheels","instantBook":true}
                """, Map.of());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return JSON.readTree(created.body()).get("id").asString();
    }

    static JsonNode text(CallToolResult result) {
        return JSON.readTree(((TextContent) result.content().getFirst()).text());
    }

    static CallToolResult call(
            io.modelcontextprotocol.client.McpSyncClient client, String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    long audit(String userId, String action, String tool) {
        return jdbc.sql("""
                        select count(*) from developer.audit_log
                         where actor_id = :u and action = :a and target_type = 'mcp_tool' and target_id = :t""")
                .param("u", userId)
                .param("a", action)
                .param("t", tool)
                .query(Long.class)
                .single();
    }

    @Test
    void discoveryAndTheChallengeTellAnAgentWhereToGetAToken() throws Exception {
        for (var path : List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
            var metadata = http("GET", path, null, null, Map.of());
            assertThat(metadata.statusCode()).as(path).isEqualTo(200);
            assertThat(metadata.body()).as(path).contains("authorization_servers");
            var json = JSON.readTree(metadata.body());
            assertThat(json.get("resource").asString()).isEqualTo(RESOURCE);
            assertThat(json.get("authorization_servers").get(0).asString()).isEqualTo(ISSUER);
            assertThat(json.get("scopes_supported").toString()).contains("\"mcp\"", "\"mcp.write\"", "\"merchant\"");
        }
        var anonymous = http("POST", "/mcp", null, INITIALIZE, MCP_HEADERS);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElseThrow())
                .startsWith("Bearer")
                .contains("resource_metadata=\"" + BASE + "/.well-known/oauth-protected-resource/mcp\"")
                .contains("scope=\"openid merchant mcp\"");
        // The OpenAPI model behind the tools is not published when the docs are off (prod).
        assertThat(http("GET", "/v3/api-docs", null, null, Map.of()).statusCode())
                .isIn(401, 403, 404);
    }

    @Test
    void aSessionReadsAndAppliesAConfirmedWriteOnce() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var listing = service(biz.merchantId(), biz.userId());
        var agent = client(agentToken(biz.userId(), "openid merchant mcp mcp.write", biz.merchantId()));
        agent.initialize();

        var tools = agent.listTools().tools().stream().map(Tool::name).toList();
        assertThat(tools)
                .contains(
                        "list_my_businesses",
                        "search_listings",
                        "get_listing",
                        "update_listing_price_stock",
                        "list_orders",
                        "kitchen_hand_off",
                        "get_earnings",
                        "list_reviews",
                        "reply_to_thread")
                .doesNotContain("list_registry_reviews", "decide_registry_review")
                .noneMatch(t ->
                        t.contains("refund") || t.contains("instant") || t.contains("bank") || t.contains("delete"));
        var write = agent.listTools().tools().stream()
                .filter(t -> t.name().equals("update_listing_price_stock"))
                .findFirst()
                .orElseThrow();
        assertThat(write.annotations().destructiveHint()).isTrue();

        assertThat(text(call(agent, "list_my_businesses", Map.of())).toString()).contains(biz.merchantId());
        assertThat(text(call(agent, "search_listings", Map.of("merchantId", biz.merchantId())))
                        .toString())
                .contains(listing);

        var args = Map.<String, Object>of(
                "merchantId", biz.merchantId(), "listingId", listing, "body", Map.of("priceCents", 10900));
        var first = text(call(agent, "update_listing_price_stock", args));
        assertThat(first.get("status").asString()).isEqualTo("confirmation_required");
        assertThat(text(call(agent, "get_listing", Map.of("merchantId", biz.merchantId(), "listingId", listing)))
                        .toString())
                .contains("9900")
                .doesNotContain("10900");

        var applied = call(agent, "update_listing_price_stock", args);
        assertThat(applied.isError()).isNotEqualTo(true);
        assertThat(text(call(agent, "get_listing", Map.of("merchantId", biz.merchantId(), "listingId", listing)))
                        .toString())
                .contains("10900");
        var again = text(call(agent, "update_listing_price_stock", args));
        assertThat(again.get("status").asString()).isEqualTo("already_done");

        var resources =
                agent.listResources().resources().stream().map(Resource::uri).toList();
        assertThat(resources).contains("northline://me", "northline://guides/agent-quickstart");
        var me = ((TextResourceContents) agent.readResource(new ReadResourceRequest("northline://me"))
                        .contents()
                        .getFirst())
                .text();
        assertThat(me).contains(biz.merchantId());
        var guide = ((TextResourceContents)
                        agent.readResource(new ReadResourceRequest("northline://guides/agent-quickstart"))
                                .contents()
                                .getFirst())
                .text();
        assertThat(guide).contains("confirmation_required", "mcp.write");
        agent.closeGracefully();

        assertThat(audit(biz.userId(), "mcp.tool_confirmation", "update_listing_price_stock"))
                .isEqualTo(1);
        assertThat(audit(biz.userId(), "mcp.tool_call", "update_listing_price_stock"))
                .isEqualTo(1);
        assertThat(audit(biz.userId(), "mcp.tool_refused", "update_listing_price_stock"))
                .isEqualTo(1);
        assertThat(audit(biz.userId(), "mcp.tool_call", "search_listings")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log
                         where actor_id = :u and target_id = 'update_listing_price_stock' and merchant_id = :m""")
                        .param("u", biz.userId())
                        .param("m", biz.merchantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void aReadOnlyGrantSeesNoWriteToolsAndCantCallOne() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var listing = service(biz.merchantId(), biz.userId());
        var reader = client(agentToken(biz.userId(), "openid merchant mcp", biz.merchantId()));
        reader.initialize();
        assertThat(reader.listTools().tools().stream().map(Tool::name).toList())
                .contains("search_listings")
                .doesNotContain("update_listing_price_stock", "mark_order_packed", "reply_to_thread");
        var refused = call(
                reader,
                "update_listing_price_stock",
                Map.of("merchantId", biz.merchantId(), "listingId", listing, "body", Map.of("priceCents", 1)));
        assertThat(refused.isError()).isTrue();
        assertThat(text(refused).get("code").asString()).isEqualTo("insufficient_scope");
        reader.closeGracefully();
        assertThat(audit(biz.userId(), "mcp.tool_refused", "update_listing_price_stock"))
                .isEqualTo(1);
    }

    @Test
    void anotherBusinessIsRefusedByTheApiItself() throws Exception {
        var mine = data.business(MerchantRole.OWNER);
        var theirs = data.business(MerchantRole.OWNER);
        var agent = client(agentToken(mine.userId(), "openid merchant mcp", mine.merchantId()));
        agent.initialize();
        var result = call(agent, "search_listings", Map.of("merchantId", theirs.merchantId()));
        assertThat(((TextContent) result.content().getFirst()).text())
                .contains("403")
                .contains("not_a_member");
        agent.closeGracefully();
    }

    @Test
    void noSecondFactorNoOtherAudienceNoScope() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var withoutMfa = token(
                biz.userId(),
                "openid merchant mcp",
                null,
                List.of("northline-mcp", "northline-api", RESOURCE),
                "northline-mcp",
                biz.merchantId());
        var response = http("POST", "/mcp", withoutMfa, INITIALIZE, MCP_HEADERS);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("insufficient_user_authentication")
                .contains("acr_values=\"mfa\"");

        var studioToken = token(
                biz.userId(),
                "openid profile merchant mcp",
                "mfa",
                List.of("studio-bff", "northline-api"),
                "studio-bff",
                biz.merchantId());
        response = http("POST", "/mcp", studioToken, INITIALIZE, MCP_HEADERS);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("invalid_token");

        var noScope = token(
                biz.userId(),
                "openid merchant",
                "mfa",
                List.of("northline-mcp", "northline-api", RESOURCE),
                "northline-mcp",
                biz.merchantId());
        response = http("POST", "/mcp", noScope, INITIALIZE, MCP_HEADERS);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("insufficient_scope")
                .contains("scope=\"mcp\"");
    }

    @Test
    void anAgentsTokenDoesNotWorkOnTheRestApiDirectly() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        var response = http(
                "GET",
                "/api/v1/me/businesses",
                agentToken(biz.userId(), "openid merchant mcp"),
                null,
                Map.of(McpAgentHeaderFilter.HEADER, "guessed"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("mcp_token");
    }

    @Test
    void staffToolsOnlyForStaffWithTheOpsScope() throws Exception {
        var staffClaims = client(staffToken());
        staffClaims.initialize();
        assertThat(staffClaims.listTools().tools().stream().map(Tool::name).toList())
                .contains("list_registry_reviews")
                .doesNotContain("decide_registry_review"); // a write: needs mcp.write as well
        staffClaims.closeGracefully();
    }

    @Test
    void aPartnerReadsItsOwnBusinessesOnly() throws Exception {
        var bound = data.business(MerchantRole.OWNER);
        var other = data.business(MerchantRole.OWNER);
        service(bound.merchantId(), bound.userId());
        var partner = client(partnerToken("partner:acme", "api.read", bound.merchantId()));
        partner.initialize();
        assertThat(partner.listTools().tools().stream().map(Tool::name).toList())
                .contains("search_listings")
                .doesNotContain("update_listing_price_stock");
        assertThat(text(call(partner, "search_listings", Map.of("merchantId", bound.merchantId())))
                        .toString())
                .contains("Tire swap");
        assertThat(((TextContent) call(partner, "search_listings", Map.of("merchantId", other.merchantId()))
                                .content()
                                .getFirst())
                        .text())
                .contains("403");
        partner.closeGracefully();
    }

    /** Northline staff's agent: {@code roles: [staff]}, {@code acr=mfa}, scope {@code mcp mcp.ops}. */
    private static String staffToken() {
        try {
            var now = java.time.Instant.now();
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject("01J9ZD3V0000000000000STAF1")
                    .audience(List.of("northline-mcp", "northline-api", RESOURCE))
                    .issueTime(java.util.Date.from(now))
                    .expirationTime(java.util.Date.from(now.plusSeconds(600)))
                    .claim("scope", "openid mcp mcp.ops")
                    .claim("roles", List.of("staff"))
                    .claim("acr", "mfa")
                    .claim("client_id", "northline-mcp")
                    .build();
            var jwt = new com.nimbusds.jwt.SignedJWT(
                    new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.ES256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new com.nimbusds.jose.crypto.ECDSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
