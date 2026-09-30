package ca.northline.auth.config;

import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Spring Authorization Server's authorization store, plus the S-19 link from each authorization to the session
 * (sign-in) it was issued from: every save (code issued, tokens, refresh, revocation) records the link and whether the
 * refresh token still works. The session id comes from the stored principal's {@code SESSION_} authority.
 */
@RequiredArgsConstructor
class SessionLinkedAuthorizations implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final SessionService sessions;

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
        if (authorization.getAttribute(Principal.class.getName()) instanceof Authentication principal) {
            SessionAuthentication.sessionIdOf(principal)
                    .ifPresent(
                            id -> sessions.authorizationSaved(authorization.getId(), id, refreshable(authorization)));
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        return delegate.findByToken(token, tokenType);
    }

    private static boolean refreshable(OAuth2Authorization authorization) {
        var refresh = authorization.getRefreshToken();
        return refresh != null && refresh.isActive();
    }
}
