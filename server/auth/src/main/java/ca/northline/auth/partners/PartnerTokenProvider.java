package ca.northline.auth.partners;

import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.PartnerTokens;
import ca.northline.auth.clients.RegisteredClients;
import java.util.ArrayList;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;

/**
 * Around Spring's client-credentials grant (S-30): a partner's token request is counted against its rate limit first
 * ({@link RateLimited} → {@code 429}), and every token issued is written to the audit log. A request without
 * {@code scope} gets the partner's registered scopes.
 */
public final class PartnerTokenProvider implements AuthenticationProvider {

    private final AuthenticationProvider clientCredentials;
    private final PartnerTokens tokens;

    public PartnerTokenProvider(AuthenticationProvider clientCredentials, PartnerTokens tokens) {
        this.clientCredentials = clientCredentials;
        this.tokens = tokens;
    }

    /** Over the partner's token limit: answered 429 with {@code Retry-After} ({@link PartnerTokenErrors}). */
    public static final class RateLimited extends OAuth2AuthenticationException {
        private static final long serialVersionUID = 1L;

        private final long retryAfterSeconds;

        RateLimited(long retryAfterSeconds) {
            super(new OAuth2Error("rate_limited", "Too many token requests. Wait and try again.", null));
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    @Override
    public @Nullable Authentication authenticate(Authentication authentication) {
        var client = authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken c
                ? c.getRegisteredClient()
                : null;
        if (client == null || !RegisteredClients.isPartner(client)) {
            return clientCredentials.authenticate(authentication);
        }
        try {
            tokens.beforeIssuing(client.getClientId());
        } catch (FlowRejected rejected) {
            throw new RateLimited(Objects.requireNonNullElse(rejected.getRetryAfterSeconds(), 60L));
        }
        // No scope asked for: the partner's registered scopes (RFC 6749 § 3.3 default), not an empty token.
        var request = authentication instanceof OAuth2ClientCredentialsAuthenticationToken cc
                        && cc.getScopes().isEmpty()
                ? new OAuth2ClientCredentialsAuthenticationToken(
                        (Authentication) cc.getPrincipal(), client.getScopes(), cc.getAdditionalParameters())
                : authentication;
        var result = clientCredentials.authenticate(request);
        if (result instanceof OAuth2AccessTokenAuthenticationToken issued) {
            var token = issued.getAccessToken();
            tokens.issued(
                    client.getClientId(),
                    new ArrayList<>(token.getScopes()),
                    RegisteredClients.partnerMerchants(client),
                    Objects.requireNonNull(token.getExpiresAt(), "access tokens expire"));
        }
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return clientCredentials.supports(authentication);
    }
}
