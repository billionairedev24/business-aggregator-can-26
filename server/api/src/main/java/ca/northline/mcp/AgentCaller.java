package ca.northline.mcp;

import ca.northline.shared.security.Authorities;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who calls the MCP server, read from their access token (S-127): a person (Studio sign-in through an MCP client:
 * {@code acr=mfa} required), a partner client (S-30, {@code client_credentials}), or — under {@code local} only — the
 * dev-auth user.
 *
 * @param subject {@code sub}: the person's id, or {@code partner:<name>}
 * @param clientId the OAuth client the token was issued to ({@code client_id} / {@code azp}), for the audit trail
 */
record AgentCaller(
        String subject,
        String clientId,
        boolean partner,
        boolean staff,
        boolean mfa,
        boolean dev,
        Set<String> scopes,
        List<String> audience) {

    static final String DEV_ISSUER = "urn:northline:dev-auth";
    static final String SCOPE_READ = "mcp";
    static final String SCOPE_WRITE = "mcp.write";
    static final String SCOPE_OPS = "mcp.ops";

    static AgentCaller of(JwtAuthenticationToken token) {
        var jwt = token.getToken();
        var authorities = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        var scopes = authorities.stream()
                .filter(a -> a.startsWith(Authorities.SCOPE_PREFIX))
                .map(a -> a.substring(Authorities.SCOPE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        var client = firstNonBlank(jwt.getClaimAsString("client_id"), jwt.getClaimAsString("azp"));
        return new AgentCaller(
                Objects.requireNonNullElse(jwt.getSubject(), "unknown"),
                client == null ? "unknown" : client,
                authorities.contains(Authorities.PARTNER),
                authorities.contains(Authorities.ROLE_PREFIX + "STAFF"),
                authorities.contains(Authorities.MFA),
                DEV_ISSUER.equals(jwt.getClaimAsString("iss")),
                scopes,
                Objects.requireNonNullElse(jwt.getAudience(), List.of()));
    }

    /** RFC 8707 / MCP: the token was issued for this MCP server (dev-auth tokens exist only under {@code local}). */
    boolean issuedFor(String resource) {
        return dev || audience.contains(resource);
    }

    /** Business tokens need a second factor (CLAUDE.md); partners are machines with their own key. */
    boolean secondFactorOk() {
        return partner || mfa;
    }

    boolean mayRead() {
        return scopes.contains(SCOPE_READ) || (partner && scopes.contains("api.read"));
    }

    boolean mayWrite() {
        return scopes.contains(SCOPE_WRITE) || (partner && scopes.contains("api.write"));
    }

    boolean mayOperate() {
        return !partner && staff && mfa && scopes.contains(SCOPE_OPS);
    }

    boolean may(AgentTools.Kind kind) {
        return switch (kind) {
            case READ -> mayRead();
            case WRITE -> mayRead() && mayWrite();
            case OPS_READ -> mayRead() && mayOperate();
            case OPS_WRITE -> mayRead() && mayOperate() && mayWrite();
        };
    }

    /** The scope a refused tool needs, for {@code insufficient_scope}. */
    static String scopeFor(AgentTools.Kind kind) {
        return switch (kind) {
            case READ -> SCOPE_READ;
            case WRITE -> SCOPE_READ + " " + SCOPE_WRITE;
            case OPS_READ -> SCOPE_READ + " " + SCOPE_OPS;
            case OPS_WRITE -> SCOPE_READ + " " + SCOPE_OPS + " " + SCOPE_WRITE;
        };
    }

    String role() {
        return partner ? "partner" : staff ? "staff" : "agent";
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }
}
