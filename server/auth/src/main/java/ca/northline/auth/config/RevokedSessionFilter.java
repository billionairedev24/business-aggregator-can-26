package ca.northline.auth.config;

import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-19: an auth-server HTTP session whose sign-in was revoked (Settings › Security, or signed out elsewhere) is
 * invalidated on its next request, before Spring Security reads it — so {@code /oauth2/authorize} can't silently issue
 * a new code to it and the JSON API answers 401. Works the same with Valkey sessions (Spring Session, cloud) and
 * in-memory ones (local/test), because it doesn't need to find the session in the store: the session finds out itself.
 * Also records "last seen" (at most once a minute). Sessions from before S-19 carry no session id and are left alone.
 */
@Slf4j
final class RevokedSessionFilter extends OncePerRequestFilter {

    private final SessionService sessions;

    RevokedSessionFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context
                && context.getAuthentication() != null) {
            var id = SessionAuthentication.sessionIdOf(context.getAuthentication());
            if (id.isPresent() && !sessions.stillOpen(id.get())) {
                log.info("Auth session of ended session {} dropped", id.get());
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
