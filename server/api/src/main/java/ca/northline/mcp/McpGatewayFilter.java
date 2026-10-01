package ca.northline.mcp;

import ca.northline.developer.api.AuditTrail;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springdoc.ai.mcp.McpRequestContextHolder;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * S-127: the policy in front of the MCP server (Streamable HTTP, {@code POST /mcp}), after authentication, per caller:
 *
 * <ul>
 *   <li><b>Audience</b> (MCP authorization, RFC 8707): only a token issued for this server — {@code aud} contains
 *       {@code northline.mcp.resource} — is accepted; any other is {@code 401 invalid_token}. A token for the Studio's
 *       BFF can't be replayed here.
 *   <li><b>Second factor:</b> a person's token without {@code acr=mfa} is {@code 403 insufficient_user_authentication}
 *       (RFC 9470, {@code acr_values="mfa"}) — business and staff tokens need it (CLAUDE.md).
 *   <li><b>Scopes:</b> {@code tools/list} shows the tools the token may use ({@link AgentTools}); a tool outside them
 *       answers a tool error {@code insufficient_scope} and never runs.
 *   <li><b>Rate limits:</b> {@code calls-per-minute} tool calls and {@code writes-per-minute} write calls per caller;
 *       over it, {@code 429} with {@code Retry-After}.
 *   <li><b>Writes are confirmed:</b> the first call of a write tool describes what it would change and runs nothing;
 *       the same call with the same arguments within {@code confirm-window} runs it. The same write repeated within
 *       {@code repeat-window} after that is answered "already done" — a retrying client can't apply it twice.
 *   <li><b>Audit:</b> every tool call is an {@code developer.audit_log} row ({@code mcp.tool_call},
 *       {@code mcp.tool_refused}, {@code mcp.tool_confirmation}) in the business's audit log, with the client id.
 *       The call itself is an HTTP request to this api with the caller's token and {@link McpAgentHeaderFilter}'s header,
 *       so the operation's own checks and audit apply exactly as for the Studio.
 *   <li><b>Resources:</b> {@code northline://me} (the caller's businesses and roles), {@code northline://merchants/{id}}
 *       (one business, as the caller sees it) and {@code northline://guides/agent-quickstart}.
 * </ul>
 *
 * Everything else (initialize, notifications, ping) passes through to the MCP server unchanged.
 */
@Slf4j
public class McpGatewayFilter extends OncePerRequestFilter {

    public static final String ENDPOINT = "/mcp";
    static final String QUICKSTART = "northline://guides/agent-quickstart";
    static final String ME = "northline://me";
    static final String MERCHANT_PREFIX = "northline://merchants/";

    private final Supplier<McpSyncServer> server;
    private final Supplier<ToolCallbackProvider> tools;
    private final McpProperties props;
    private final AgentCalls calls;
    private final AuditTrail audit;
    private final JsonMapper json;
    private final Clock clock;
    private final McpAgentHeaderFilter agentHeader;

    public McpGatewayFilter(
            Supplier<McpSyncServer> server,
            Supplier<ToolCallbackProvider> tools,
            McpProperties props,
            AgentCalls calls,
            AuditTrail audit,
            JsonMapper json,
            Clock clock,
            McpAgentHeaderFilter agentHeader) {
        this.server = server;
        this.tools = tools;
        this.props = props;
        this.calls = calls;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.agentHeader = agentHeader;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !ENDPOINT.equals(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response); // the security chain answered 401 already
            return;
        }
        var caller = AgentCaller.of(token);
        if (!caller.issuedFor(props.resource())) {
            challenge(response, 401, "invalid_token", "The access token was not issued for this MCP server.", null);
            return;
        }
        if (!caller.secondFactorOk()) {
            response.setHeader(
                    "WWW-Authenticate",
                    "Bearer error=\"insufficient_user_authentication\", error_description=\"Sign in with your second"
                            + " factor (passkey or authenticator)\", acr_values=\"mfa\", resource_metadata=\""
                            + McpSecurityConfiguration.metadataUrl(props) + "\"");
            problem(response, 403, "mfa_required", "Sign in with your second factor to use Northline tools.");
            return;
        }
        if (!caller.mayRead()) {
            challenge(response, 403, "insufficient_scope", "The token needs the mcp scope.", AgentCaller.SCOPE_READ);
            return;
        }
        var body = request.getInputStream().readAllBytes();
        var cached = new CachedBody(request, body);
        JsonNode message;
        try {
            message = body.length == 0 ? null : json.readTree(body);
        } catch (RuntimeException e) {
            chain.doFilter(cached, response);
            return;
        }
        if (message == null || !message.isObject()) {
            chain.doFilter(cached, response); // a batch or something the server will refuse itself
            return;
        }
        var id = message.get("id");
        switch (message.path("method").asString("")) {
            case "tools/list" -> reply(response, id, toolList(caller));
            case "resources/list" -> reply(response, id, resourceList());
            case "resources/templates/list" -> reply(response, id, templateList());
            case "resources/read" ->
                reply(
                        response,
                        id,
                        read(request, message.path("params").path("uri").asString("")));
            case "tools/call" -> {
                if (guard(caller, message.path("params"), response, id)) {
                    chain.doFilter(cached, response);
                }
            }
            default -> chain.doFilter(cached, response);
        }
    }

    // ── tools/call ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Scope, rate limit, confirmation, repeat protection and audit; false when the call was answered here. */
    private boolean guard(AgentCaller caller, JsonNode params, HttpServletResponse response, @Nullable JsonNode id)
            throws IOException {
        var name = params.path("name").asString("");
        var arguments = params.path("arguments");
        var merchantId = arguments.path("merchantId").asString(null);
        var tool = AgentTools.named(name).orElse(null);
        if (tool == null) {
            return true; // unknown tool: the MCP server answers it
        }
        if (!caller.may(tool.kind())) {
            record(caller, merchantId, "mcp.tool_refused", tool, Map.of("reason", "insufficient_scope"));
            toolError(
                    response,
                    id,
                    403,
                    "insufficient_scope",
                    tool.name() + " needs the scope " + AgentCaller.scopeFor(tool.kind())
                            + " (and, for staff tools, a staff sign-in with a second factor).");
            return false;
        }
        var who = hash(caller.subject());
        if (!calls.allow("call:" + who, props.callsPerMinute())
                || (tool.kind().writes() && !calls.allow("write:" + who, props.writesPerMinute()))) {
            record(caller, merchantId, "mcp.tool_refused", tool, Map.of("reason", "rate_limited"));
            var retry = 60 - clock.instant().getEpochSecond() % 60;
            response.setHeader("Retry-After", Long.toString(retry));
            problem(response, 429, "mcp_rate_limited", "Too many tool calls; try again in " + retry + " s.");
            return false;
        }
        if (!tool.kind().writes()) {
            record(
                    caller,
                    merchantId,
                    "mcp.tool_call",
                    tool,
                    Map.of("kind", tool.kind().code()));
            return true;
        }
        var key = hash(caller.subject() + "|" + tool.name() + "|" + canonical(arguments));
        var done = calls.get("done:" + key);
        if (done.isPresent()) {
            record(caller, merchantId, "mcp.tool_refused", tool, Map.of("reason", "repeated"));
            toolResult(
                    response,
                    id,
                    false,
                    Map.of(
                            "status",
                            "already_done",
                            "detail",
                            "This exact change was applied at " + done.get() + ". It is not applied twice; change an"
                                    + " argument to make another change."));
            return false;
        }
        if (calls.remove("pending:" + key)) {
            calls.putIfAbsent("done:" + key, clock.instant().toString(), props.repeatWindow());
            record(
                    caller,
                    merchantId,
                    "mcp.tool_call",
                    tool,
                    Map.of("kind", tool.kind().code(), "confirmed", true));
            return true;
        }
        calls.putIfAbsent("pending:" + key, clock.instant().toString(), props.confirmWindow());
        record(
                caller,
                merchantId,
                "mcp.tool_confirmation",
                tool,
                Map.of("kind", tool.kind().code()));
        var described = new LinkedHashMap<String, Object>();
        described.put("status", "confirmation_required");
        described.put("tool", tool.name());
        described.put("change", tool.description());
        described.put("arguments", arguments);
        described.put(
                "detail",
                "Nothing has changed yet. Show the person this change; if they agree, call " + tool.name()
                        + " again with exactly the same arguments within "
                        + props.confirmWindow().toMinutes()
                        + " minutes.");
        toolResult(response, id, false, described);
        return false;
    }

    private void record(
            AgentCaller caller,
            @Nullable String merchantId,
            String action,
            AgentTools.Tool tool,
            Map<String, ?> after) {
        var details = new LinkedHashMap<String, Object>(after);
        details.put("client", caller.clientId());
        try {
            audit.record(new AuditTrail.Entry(
                    merchantId, caller.subject(), caller.role(), action, "mcp_tool", tool.name(), null, details));
        } catch (RuntimeException e) {
            log.warn("MCP audit row not written ({} {}): {}", action, tool.name(), e.toString());
        }
    }

    // ── tools/list, resources ───────────────────────────────────────────────────────────────────────────────────

    private ObjectNode toolList(AgentCaller caller) {
        var result = json.createObjectNode();
        var list = result.putArray("tools");
        for (var tool : server.get().listTools()) {
            var ours = AgentTools.named(tool.name()).orElse(null);
            if (ours == null || !caller.may(ours.kind())) {
                continue;
            }
            var node = list.addObject();
            node.put("name", tool.name());
            if (tool.title() != null) {
                node.put("title", tool.title());
            }
            node.put("description", ours.description());
            node.set(
                    "inputSchema",
                    json.valueToTree(tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema()));
            var annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", !ours.kind().writes());
            annotations.put("destructiveHint", ours.kind().writes());
            annotations.put("idempotentHint", !ours.kind().writes());
            annotations.put("openWorldHint", false);
        }
        return result;
    }

    private ObjectNode resourceList() {
        var result = json.createObjectNode();
        var list = result.putArray("resources");
        list.addObject()
                .put("uri", ME)
                .put("name", "me")
                .put("title", "Your businesses")
                .put("description", "The businesses you act for and your role in each")
                .put("mimeType", MediaType.APPLICATION_JSON_VALUE);
        list.addObject()
                .put("uri", QUICKSTART)
                .put("name", "agent-quickstart")
                .put("title", "Agent quickstart")
                .put("description", "What the tools do, which ones change data, confirmation, limits")
                .put("mimeType", "text/markdown");
        return result;
    }

    private ObjectNode templateList() {
        var result = json.createObjectNode();
        result.putArray("resourceTemplates")
                .addObject()
                .put("uriTemplate", MERCHANT_PREFIX + "{merchantId}")
                .put("name", "business")
                .put("title", "One business")
                .put("description", "A business you belong to, as your role sees it")
                .put("mimeType", MediaType.APPLICATION_JSON_VALUE);
        return result;
    }

    private ObjectNode read(HttpServletRequest request, String uri) {
        String mime = MediaType.APPLICATION_JSON_VALUE;
        String text;
        if (QUICKSTART.equals(uri)) {
            mime = "text/markdown";
            text = quickstart();
        } else if (ME.equals(uri)) {
            text = callTool(request, "list_my_businesses", "{}");
        } else if (uri.startsWith(MERCHANT_PREFIX) && uri.length() > MERCHANT_PREFIX.length()) {
            var input = json.createObjectNode().put("merchantId", uri.substring(MERCHANT_PREFIX.length()));
            text = callTool(request, "get_business", json.writeValueAsString(input));
        } else {
            text = json.writeValueAsString(Map.of("status", 404, "code", "not_found", "detail", "No resource " + uri));
        }
        var result = json.createObjectNode();
        result.putArray("contents")
                .addObject()
                .put("uri", uri)
                .put("mimeType", mime)
                .put("text", text);
        return result;
    }

    /** Runs a read tool as the caller: the same HTTP call a {@code tools/call} makes, with the caller's headers. */
    private String callTool(HttpServletRequest request, String name, String input) {
        for (var callback : tools.get().getToolCallbacks()) {
            if (!callback.getToolDefinition().name().equals(name)) {
                continue;
            }
            var headers = new LinkedHashMap<String, String>();
            var authorization = request.getHeader("Authorization");
            if (authorization != null) {
                headers.put("Authorization", authorization);
            }
            var devUser = request.getHeader("X-Dev-User");
            if (devUser != null) {
                headers.put("X-Dev-User", devUser);
            }
            headers.put(McpAgentHeaderFilter.HEADER, agentHeader.secret());
            var before = McpRequestContextHolder.getHeaders();
            McpRequestContextHolder.setHeaders(headers);
            try {
                return callback.call(input);
            } finally {
                if (before == null) {
                    McpRequestContextHolder.clear();
                } else {
                    McpRequestContextHolder.setHeaders(before);
                }
            }
        }
        return json.writeValueAsString(Map.of("status", 404, "code", "not_found", "detail", "No tool " + name));
    }

    static String quickstart() {
        try (var in = new ClassPathResource("mcp/agent-quickstart.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Northline agent quickstart\n\nSee docs/runbooks/mcp.md.";
        }
    }

    // ── responses ───────────────────────────────────────────────────────────────────────────────────────────────

    private void toolError(HttpServletResponse response, @Nullable JsonNode id, int status, String code, String detail)
            throws IOException {
        toolResult(response, id, true, Map.of("status", status, "code", code, "detail", detail));
    }

    private void toolResult(HttpServletResponse response, @Nullable JsonNode id, boolean error, Map<String, ?> content)
            throws IOException {
        var result = json.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", json.writeValueAsString(content));
        result.put("isError", error);
        reply(response, id, result);
    }

    private void reply(HttpServletResponse response, @Nullable JsonNode id, JsonNode result) throws IOException {
        var out = json.createObjectNode();
        out.put("jsonrpc", "2.0");
        if (id != null) {
            out.set("id", id);
        }
        out.set("result", result);
        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(json.writeValueAsBytes(out));
    }

    private void challenge(
            HttpServletResponse response, int status, String error, String detail, @Nullable String scope)
            throws IOException {
        var header = new StringBuilder("Bearer error=\"")
                .append(error)
                .append("\", error_description=\"")
                .append(detail)
                .append('"');
        if (scope != null) {
            header.append(", scope=\"").append(scope).append('"');
        }
        header.append(", resource_metadata=\"")
                .append(McpSecurityConfiguration.metadataUrl(props))
                .append('"');
        response.setHeader("WWW-Authenticate", header.toString());
        problem(response, status, error, detail);
    }

    private void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream()
                .write(json.writeValueAsBytes(Map.of("status", status, "code", code, "detail", detail)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** The arguments with object keys sorted, so the same change hashes the same however the client orders it. */
    private String canonical(JsonNode node) {
        return json.writeValueAsString(sorted(node));
    }

    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            var map = new TreeMap<String, Object>();
            node.properties().forEach(e -> map.put(e.getKey(), sorted(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            var list = new java.util.ArrayList<Object>();
            node.forEach(v -> list.add(sorted(v)));
            return list;
        }
        return node;
    }

    static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request with its body read once and replayable for the MCP server. */
    static final class CachedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
