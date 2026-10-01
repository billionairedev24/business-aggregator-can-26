package ca.northline.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-127: puts {@value #HEADER} on every request to the MCP endpoint, before anything reads its headers. springdoc
 * forwards the MCP request's headers on each tool's HTTP call back to this api, so the call arrives with the caller's
 * token and this header — which tells {@link McpTokenConfinement} it comes from the MCP server, not from outside. The
 * value is a random secret of this process (the tool calls go to {@code localhost}, the same process); a client's own
 * {@value #HEADER} is replaced.
 */
public class McpAgentHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Northline-Agent";

    private final String secret;

    public McpAgentHeaderFilter() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String secret() {
        return secret;
    }

    /** Constant-time check of a request's header. */
    boolean fromMcpServer(@Nullable String value) {
        return value != null
                && MessageDigest.isEqual(
                        value.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(McpGatewayFilter.ENDPOINT) || path.startsWith(McpGatewayFilter.ENDPOINT + "/"));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public @Nullable String getHeader(String name) {
                        return HEADER.equalsIgnoreCase(name) ? secret : super.getHeader(name);
                    }

                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        return HEADER.equalsIgnoreCase(name)
                                ? Collections.enumeration(List.of(secret))
                                : super.getHeaders(name);
                    }

                    @Override
                    public Enumeration<String> getHeaderNames() {
                        var names = new ArrayList<>(Collections.list(super.getHeaderNames()));
                        if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                            names.add(HEADER);
                        }
                        return Collections.enumeration(names);
                    }
                },
                response);
    }
}
