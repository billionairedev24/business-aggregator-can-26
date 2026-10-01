package ca.northline.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-127 MCP authorization, the resource-server side (MCP specification 2025-11-25 § Authorization):
 *
 * <ul>
 *   <li>OAuth 2.0 Protected Resource Metadata (RFC 9728) at {@code /.well-known/oauth-protected-resource/mcp} (the
 *       path form) and {@code /.well-known/oauth-protected-resource} (the root form): the MCP server's canonical URI,
 *       northline-auth as its authorization server, the scopes;
 *   <li>every {@code 401} on {@code /mcp} carries {@code WWW-Authenticate: Bearer resource_metadata="…",
 *       scope="openid merchant mcp"}, so an MCP client discovers where and what to ask for.
 * </ul>
 *
 * The security chain itself is declared with the api's others ({@code ca.northline.config.SecurityConfig}).
 */
public final class McpSecurityConfiguration {

    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    /** What an MCP client asks for to use the tools; {@code mcp.write} / {@code mcp.ops} are asked when needed. */
    public static final String DEFAULT_SCOPES = "openid merchant mcp";

    public static final List<String> SCOPES = List.of(
            "openid", "profile", "merchant", AgentCaller.SCOPE_READ, AgentCaller.SCOPE_WRITE, AgentCaller.SCOPE_OPS);

    private McpSecurityConfiguration() {}

    /** RFC 9728 § 3.1: the metadata URL of a resource with a path is the well-known path followed by that path. */
    public static String metadataUrl(McpProperties props) {
        var resource = URI.create(props.resource());
        var path = resource.getRawPath() == null || "/".equals(resource.getRawPath()) ? "" : resource.getRawPath();
        var port = resource.getPort() == -1 ? "" : ":" + resource.getPort();
        return resource.getScheme() + "://" + resource.getHost() + port + METADATA_PATH + path;
    }

    /** Bearer challenges with the RFC 9728 pointer and the scopes to request. */
    public static AuthenticationEntryPoint entryPoint(McpProperties props) {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) -> {
            bearer.commence(request, response, e);
            var header = response.getHeader("WWW-Authenticate");
            var params = "resource_metadata=\"" + metadataUrl(props) + "\", scope=\"" + DEFAULT_SCOPES + "\"";
            response.setHeader(
                    "WWW-Authenticate",
                    header == null || "Bearer".equals(header) ? "Bearer " + params : header + ", " + params);
        };
    }

    /** Serves the protected resource metadata (both well-known forms); public, cacheable. */
    public static OncePerRequestFilter metadataEndpoint(McpProperties props, JsonMapper json) {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                var path = request.getRequestURI();
                return !"GET".equals(request.getMethod()) || !path.startsWith(METADATA_PATH);
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var metadata = Map.of(
                        "resource", props.resource(),
                        "authorization_servers", List.of(props.authorizationServer()),
                        "scopes_supported", SCOPES,
                        "bearer_methods_supported", List.of("header"),
                        "resource_name", "Northline");
                response.setStatus(200);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Cache-Control", "public, max-age=300");
                response.getOutputStream().write(json.writeValueAsBytes(metadata));
            }
        };
    }
}
