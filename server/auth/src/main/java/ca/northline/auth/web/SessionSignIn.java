package ca.northline.auth.web;

import ca.northline.auth.domain.Factor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    void signIn(String userId, Collection<Factor> factors, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId(); // session fixation
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        var at = clock.instant();
        factors.forEach(f -> authorities.add(
                FactorGrantedAuthority.withAuthority(f.authority()).issuedAt(at).build()));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, authorities);
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
