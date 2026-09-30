package ca.northline.auth.config;

import ca.northline.auth.application.RefreshTokenReuse;
import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.clients.RegisteredClients;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * S-29: refresh-token reuse detection for public clients, around Spring Authorization Server's JDBC store. Every save
 * of a public client's authorization remembers its current refresh token; a refresh token the store no longer knows
 * but that was issued to a still-existing family is a reused, rotated token: {@link RefreshTokenReuse#revoke} revokes
 * the family and ends its sign-in, and the request fails as {@code invalid_grant} like any unknown token.
 *
 * <p>Confidential clients (the BFFs) are left out: their tokens never leave the server, and two replicas refreshing one
 * browser session at the same moment would look like reuse.
 */
@RequiredArgsConstructor
class RefreshTokenReuseDetection implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final RegisteredClientRepository clients;
    private final RefreshTokenReuse reuse;

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
        var refresh = authorization.getRefreshToken();
        if (refresh == null || !refresh.isActive()) {
            return;
        }
        var token = refresh.getToken();
        var client = clients.findById(authorization.getRegisteredClientId());
        if (client != null
                && RegisteredClients.isPublic(client)
                && token.getIssuedAt() != null
                && token.getExpiresAt() != null) {
            reuse.issued(token.getTokenValue(), authorization.getId(), token.getIssuedAt(), token.getExpiresAt());
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
        var found = delegate.findByToken(token, tokenType);
        if (found != null || (tokenType != null && !OAuth2TokenType.REFRESH_TOKEN.equals(tokenType))) {
            return found;
        }
        reuse.familyOf(token).map(delegate::findById).ifPresent(family -> {
            var sessionId = family.getAttribute(Principal.class.getName()) instanceof Authentication principal
                    ? SessionAuthentication.sessionIdOf(principal).orElse(null)
                    : null;
            var client = clients.findById(family.getRegisteredClientId());
            reuse.revoke(
                    family.getPrincipalName(),
                    sessionId,
                    family.getId(),
                    client == null ? family.getRegisteredClientId() : client.getClientId());
        });
        return null;
    }
}
