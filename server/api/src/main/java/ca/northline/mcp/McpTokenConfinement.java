package ca.northline.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-127: a token issued for the MCP server ({@code aud} contains {@code northline.mcp.resource}) works on {@code /api}
 * only for the MCP server's own tool calls ({@link McpAgentHeaderFilter}'s secret header). Taken out of the agent and
 * sent to the REST api directly, it is refused ({@code 403 mcp_token}) — what the person consented to is the agent's
 * tools, not the whole api. Runs after Spring Security (the token is already validated).
 */
public class McpTokenConfinement extends OncePerRequestFilter {

    private final McpProperties props;
    private final McpAgentHeaderFilter agentHeader;

    public McpTokenConfinement(McpProperties props, McpAgentHeaderFilter agentHeader) {
        this.props = props;
        this.agentHeader = agentHeader;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.getToken().getAudience() != null
                && token.getToken().getAudience().contains(props.resource())
                && !agentHeader.fromMcpServer(request.getHeader(McpAgentHeaderFilter.HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://northline.ca/problems/forbidden","title":"Forbidden","status":403,\
                    "detail":"This token was issued for Northline's MCP server: use it through your agent.",\
                    "code":"mcp_token"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
