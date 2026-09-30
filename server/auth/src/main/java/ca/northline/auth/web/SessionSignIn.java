package ca.northline.auth.web;

import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Establishes the auth server's own session once the JSON flow succeeds. {@code /oauth2/authorize} then finds an
 * authenticated session and issues the code to the BFF without showing anything (the Studio's hand-off).
 *
 * <p>The authentication is a plain {@link UsernamePasswordAuthenticationToken} (principal = {@code identity.users} id)
 * with {@code FACTOR_*} {@link FactorGrantedAuthority factor authorities} (their time becomes {@code auth_time}), so it round-trips through the authorization service's JSON storage unchanged.
 */
@Component
@RequiredArgsConstructor
class SessionSignIn {

    private final Clock clock;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    /** {@code sessionId}: the sign-in's {@code identity.sessions} id (S-19), kept as a {@code SESSION_} authority. */
    void signIn(
            String userId,
            Collection<Factor> factors,
            String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId(); // session fixation
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        authorities.add(SessionAuthentication.sessionAuthority(sessionId));
        var at = clock.instant();
        factors.forEach(f -> authorities.add(
                FactorGrantedAuthority.withAuthority(f.authority()).issuedAt(at).build()));
        save(UsernamePasswordAuthenticationToken.authenticated(userId, null, authorities), request, response);
    }

    /**
     * Step-up succeeded (S-19): the factor counts as used now, so Settings › Security changes that need a recent
     * second factor go through. Same sign-in, but a new session id (S-20): the session just gained privileges, so an id
     * that leaked before the confirmation is worth nothing after it.
     */
    void refreshFactor(
            Authentication current, Factor factor, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation (S-20)
        }
        var at = clock.instant();
        List<GrantedAuthority> authorities = new ArrayList<>(current.getAuthorities().stream()
                .filter(a -> !factor.authority().equals(a.getAuthority()))
                .toList());
        authorities.add(FactorGrantedAuthority.withAuthority(factor.authority())
                .issuedAt(at)
                .build());
        save(
                UsernamePasswordAuthenticationToken.authenticated(current.getName(), null, authorities),
                request,
                response);
    }

    private void save(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    void signOut(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }
}
