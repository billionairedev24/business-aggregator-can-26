package ca.northline.bff.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.bff.*}.
 *
 * @param registrationId the OAuth client registration used for sign-in and token relay ({@code studio})
 * @param apiUri where {@code /api/**} is relayed
 * @param revocationUri the auth server's token revocation endpoint (sign-out revokes the refresh token)
 * @param signInPage where a failed OAuth callback lands (the SPA's sign-in page, relative to the app origin)
 * @param introspectionUri the auth server's token introspection endpoint (S-19: is the session's sign-in revoked?)
 * @param sessionCheckInterval how often, at most, a session's refresh token is introspected (S-19)
 */
@ConfigurationProperties("northline.bff")
public record BffProperties(
        @DefaultValue("studio") String registrationId,
        @DefaultValue("http://localhost:8080") String apiUri,
        @DefaultValue("http://localhost:9000/oauth2/revoke") String revocationUri,
        @DefaultValue("/sign-in") String signInPage,

        @DefaultValue("http://localhost:9000/oauth2/introspect")
        String introspectionUri,

        @DefaultValue("60s") Duration sessionCheckInterval) {}
