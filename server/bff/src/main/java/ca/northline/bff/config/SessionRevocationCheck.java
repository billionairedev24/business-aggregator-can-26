package ca.northline.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-19: the BFF session ends soon after its sign-in is revoked at northline-auth (Settings › Security, or signed out
 * elsewhere), not only when the refresh token next fails:
 *
 * <ul>
 *   <li>at most every {@code northline.bff.session-check-interval} (default 60 s) per session, the refresh token (or
 *       the access token when there is none) is introspected at {@code /oauth2/introspect} (RFC 7662); "inactive" ends
 *       the session. An unreachable auth server fails open (logged): the refresh below still catches it within the
 *       10-minute access token life.
 *   <li>a token refresh answered {@code invalid_grant} (revoked or expired refresh token) during the relay ends the
 *       session too, with 401 instead of a 500.
 * </ul>
 *
 * Ending = the authorized client is forgotten, the HTTP session invalidated and the request continues anonymous, so
 * {@code /bff/session} answers 401 and the Studio shows its sign-in page. The time of the last check lives in the
 * session, so replicas share it (Valkey).
 */
@Slf4j
final class SessionRevocationCheck extends OncePerRequestFilter {

    static final String CHECKED_AT = "nl.bff.session-checked-at";

    private final OAuth2AuthorizedClientRepository clients;
    private final TokenIntrospection introspection;
    private final BffProperties props;
    private final Clock clock;

    SessionRevocationCheck(
            OAuth2AuthorizedClientRepository clients,
            TokenIntrospection introspection,
            BffProperties props,
            Clock clock) {
        this.clients = clients;
        this.introspection = introspection;
        this.props = props;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = signedIn();
        if (token != null && due(request)) {
            var client = clients.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), token, request);
            if (client != null && !introspection.active(client)) { // no client: nothing to relay, nothing to check
                end(token, request, response, "token inactive");
            }
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            var current = signedIn();
            if (current == null || !refreshRefused(e) || response.isCommitted()) {
                throw e;
            }
            end(current, request, response, "refresh refused");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://northline.ca/problems/session-ended","title":"Unauthorized","status":401,\
                    "detail":"Your session has ended. Sign in again.","code":"session_ended"}""");
        }
    }

    private static @Nullable OAuth2AuthenticationToken signedIn() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken t ? t : null;
    }

    private boolean due(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return false;
        }
        var now = clock.instant();
        if (session.getAttribute(CHECKED_AT) instanceof Instant last
                && last.plus(props.sessionCheckInterval()).isAfter(now)) {
            return false;
        }
        session.setAttribute(CHECKED_AT, now);
        return true;
    }

    private void end(
            OAuth2AuthenticationToken token, HttpServletRequest request, HttpServletResponse response, String why) {
        log.info("BFF session of {} ended: {}", token.getName(), why);
        clients.removeAuthorizedClient(token.getAuthorizedClientRegistrationId(), token, request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** {@code invalid_grant} from the token endpoint, somewhere in the cause chain (the relay wraps it). */
    private static boolean refreshRefused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OAuth2AuthorizationException oauth
                    && OAuth2ErrorCodes.INVALID_GRANT.equals(oauth.getError().getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}
