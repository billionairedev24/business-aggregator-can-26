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
 * Who may read the developer docs MCP server (S-128). {@code open} (local): anyone who reaches it. {@code staff} (the
 * cloud profiles): a token addressed to the docs server ({@code aud} = {@code northline.devdocs.resource}, RFC 8707)
 * of Northline staff with a second factor — internal documentation (runbooks, decisions, internal APIs) is not for
 * merchants' or partners' agents. Spring Security's MCP chain has already validated the token (401 otherwise).
 */
class DevDocsAccessFilter extends OncePerRequestFilter {

    private final DevDocsProperties props;

    DevDocsAccessFilter(DevDocsProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (props.access() == DevDocsProperties.Access.OPEN) {
            chain.doFilter(request, response);
            return;
        }
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            refuse(response, 401, "invalid_token", "A bearer token for " + props.resource() + " is required.", "");
            return;
        }
        var caller = AgentCaller.of(token);
        if (!caller.issuedFor(props.resource())) {
            refuse(response, 401, "invalid_token", "This token was not issued for " + props.resource() + ".", "");
        } else if (caller.partner() || !caller.staff()) {
            refuse(
                    response,
                    403,
                    "insufficient_scope",
                    "The developer docs server is for Northline staff.",
                    ", scope=\"openid mcp\"");
        } else if (!caller.mfa()) {
            refuse(
                    response,
                    403,
                    "insufficient_user_authentication",
                    "Sign in with your second factor.",
                    ", acr_values=\"mfa\"");
        } else {
            chain.doFilter(request, response);
        }
    }

    private void refuse(HttpServletResponse response, int status, String error, String description, String extra)
            throws IOException {
        response.setStatus(status);
        response.setHeader(
                "WWW-Authenticate",
                "Bearer error=\"" + error + "\", error_description=\"" + description + "\", resource_metadata=\""
                        + McpSecurityConfiguration.metadataUrl(props.resource()) + "\"" + extra);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
