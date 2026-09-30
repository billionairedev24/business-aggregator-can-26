package ca.northline.auth.config;

import ca.northline.auth.application.LoginPages;
import ca.northline.auth.domain.Factor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-62: consumers may sign in with a code to their phone alone (no second factor). Such a session must never become a
 * Studio or console session: an authorization request of a client in {@code northline.auth.mfa-required-clients}
 * (studio-bff, console-bff) from a session without a second factor gets no code — the browser is sent to that client's
 * sign-in page, where signing in with a passkey / authenticator / backup code replaces the session. (The api refuses
 * merchant and staff endpoints without {@code acr=mfa} anyway; this keeps the business apps from starting at all.)
 */
@Slf4j
final class MfaRequiredClients extends OncePerRequestFilter {

    static final String AUTHORIZE = "/oauth2/authorize";

    private final LoginPages pages;

    MfaRequiredClients(LoginPages pages) {
        this.pages = pages;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AUTHORIZE.equals(
                request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (pages.requiresMfa(clientId) && signedInWithoutSecondFactor(auth)) {
            log.info("No code for {}: the session has no second factor", clientId);
            response.sendRedirect(pages.forClient(clientId));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean signedInWithoutSecondFactor(@Nullable Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false; // not signed in: the usual login redirect
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .flatMap(a -> Factor.fromAuthority(a).stream())
                .noneMatch(Factor::isSecondFactor);
    }
}
