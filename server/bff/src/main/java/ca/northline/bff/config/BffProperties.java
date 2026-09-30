package ca.northline.bff.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.bff.*}.
 *
 * @param registrationId the OAuth client registration used for sign-in and token relay ({@code studio}; the consumer
 *     profile's is {@code northline})
 * @param apiUri where {@code /api/**} is relayed
 * @param revocationUri the auth server's token revocation endpoint (sign-out revokes the refresh token)
 * @param signInPage where a failed OAuth callback lands (the SPA's sign-in page, relative to the app origin)
 * @param introspectionUri the auth server's token introspection endpoint (S-19: is the session's sign-in revoked?)
 * @param sessionCheckInterval how often, at most, a session's refresh token is introspected (S-19)
 * @param csrfCookieName the CSRF token cookie the Studio reads ({@code XSRF-TOKEN} locally; {@code __Host-XSRF-TOKEN} in
 *     the cloud, S-20: a sibling subdomain can't plant or overwrite a {@code __Host-} cookie)
 * @param guests S-45 consumer-bff: people browse without signing in — {@code /api/**} is relayed without a token for
 *     them and {@code GET /bff/session} answers 200 with a guest id instead of 401 (false: the Studio's behaviour)
 * @param clientCityHeader S-45: request header with the visitor's city, set by the CDN / ingress (empty = none); shown
 *     as the location pill's first guess
 */
@ConfigurationProperties("northline.bff")
public record BffProperties(
        @DefaultValue("studio") String registrationId,
        @DefaultValue("http://localhost:8080") String apiUri,
        @DefaultValue("http://localhost:9000/oauth2/revoke") String revocationUri,
        @DefaultValue("/sign-in") String signInPage,

        @DefaultValue("http://localhost:9000/oauth2/introspect")
        String introspectionUri,

        @DefaultValue("60s") Duration sessionCheckInterval,
        @DefaultValue("XSRF-TOKEN") String csrfCookieName,
        @DefaultValue("false") boolean guests,
        @DefaultValue("") String clientCityHeader) {}
