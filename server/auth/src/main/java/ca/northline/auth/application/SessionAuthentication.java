package ca.northline.auth.application;

import ca.northline.auth.domain.Factor;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * What the auth server's session authentication carries besides the user id (S-19): the id of the sign-in it came from
 * ({@code identity.sessions}, as a {@code SESSION_<id>} authority) and when each factor was used ({@code FACTOR_*}
 * {@link FactorGrantedAuthority factor authorities}). Both are plain authorities, so they round-trip through the
 * authorization server's JSON storage of the principal: token refreshes see the same session id.
 */
public final class SessionAuthentication {

    public static final String SESSION_AUTHORITY_PREFIX = "SESSION_";

    private SessionAuthentication() {}

    public static GrantedAuthority sessionAuthority(String sessionId) {
        return new SimpleGrantedAuthority(SESSION_AUTHORITY_PREFIX + sessionId);
    }

    /** The sign-in this authentication belongs to; empty for sessions created before S-19. */
    public static Optional<String> sessionIdOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(a -> a.startsWith(SESSION_AUTHORITY_PREFIX))
                .map(a -> a.substring(SESSION_AUTHORITY_PREFIX.length()))
                .findFirst();
    }

    /** When a second factor (passkey, authenticator, backup code) was last used in this session. */
    public static Optional<Instant> lastSecondFactorAt(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .filter(FactorGrantedAuthority.class::isInstance)
                .map(FactorGrantedAuthority.class::cast)
                .filter(a -> Factor.fromAuthority(a.getAuthority())
                        .map(Factor::isSecondFactor)
                        .orElse(false))
                .map(FactorGrantedAuthority::getIssuedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
    }

    /** The person behind a request, for the Settings › Security use cases. */
    public static Caller caller(Authentication authentication) {
        return new Caller(
                authentication.getName(),
                sessionIdOf(authentication).orElse(null),
                lastSecondFactorAt(authentication).orElse(null));
    }
}
