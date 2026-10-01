package ca.northline.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-90 console-bff ({@code northline.bff.staff-only}): the platform console is for Northline staff who signed in with a
 * second factor. The ID token northline-auth issues for the {@code console} scope carries the platform {@code roles}
 * and {@code acr}. A sign-in without {@code staff} or without {@code acr=mfa} is ended at once — refresh token revoked,
 * session dropped — and the browser lands on the console's sign-in page with {@code ?error=staff_only} or
 * {@code ?error=mfa_required}. Every later request re-checks (a session can't be widened, but the check costs nothing).
 * The api refuses staff endpoints without the role and the second factor anyway; this keeps the rest out of the
 * console entirely.
 */
@Slf4j
final class StaffGate {

    static final String STAFF = "staff";
    static final String MFA = "mfa";

    private StaffGate() {}

    /** Why this sign-in may not use the console ({@code staff_only} | {@code mfa_required}), or empty. */
    static Optional<String> refusal(@Nullable Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof OidcUser user)) {
            return Optional.empty(); // not signed in: the usual 401
        }
        var roles = Objects.requireNonNullElse(user.getClaimAsStringList("roles"), List.<String>of());
        if (!roles.contains(STAFF)) {
            return Optional.of("staff_only");
        }
        return MFA.equals(user.getClaimAsString("acr")) ? Optional.empty() : Optional.of("mfa_required");
    }

    /** After the OAuth callback: staff go on to {@code next}; anyone else is signed out and sent to the sign-in page. */
    static AuthenticationSuccessHandler signIn(
            AuthenticationSuccessHandler next, RevokeTokensOnLogout revoke, BffProperties props) {
        return (request, response, authentication) -> {
            var refused = refusal(authentication);
            if (refused.isEmpty()) {
                next.onAuthenticationSuccess(request, response, authentication);
                return;
            }
            log.info("Console sign-in refused: {} (user {})", refused.get(), authentication.getName());
            end(request, response, authentication, revoke);
            response.sendRedirect(props.signInPage() + "?error=" + refused.get());
        };
    }

    /** Every request: a session that doesn't pass is ended and answered 401 (the console then shows its sign-in). */
    static OncePerRequestFilter filter(RevokeTokensOnLogout revoke) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && refusal(auth).isPresent()) {
                    end(request, response, auth, revoke);
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static void end(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication auth,
            RevokeTokensOnLogout revoke) {
        revoke.logout(request, response, auth);
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
