package ca.northline.auth.federation;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.federation.*} (S-18, docs/runbooks/federation.md): the Google and Apple client registrations,
 * per environment from the environment / secrets manager. A provider without a client id is off: its button answers
 * "not available" instead of failing. The endpoints default to the providers' own (tests point them at WireMock).
 */
@ConfigurationProperties("northline.auth.federation")
public record FederationProperties(
        @DefaultValue Google google, @DefaultValue Apple apple) {

    /** Google Cloud console → OAuth client (type Web application). */
    public record Google(
            @Nullable String clientId,
            @Nullable String clientSecret,

            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth")
            String authorizationUri,

            @DefaultValue("https://oauth2.googleapis.com/token")
            String tokenUri,

            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs")
            String jwkSetUri,

            @DefaultValue("https://openidconnect.googleapis.com/v1/userinfo")
            String userInfoUri,

            @DefaultValue({"https://accounts.google.com", "accounts.google.com"})
            List<String> issuers) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank();
        }
    }

    /**
     * Apple Developer → Services ID ({@code clientId}), team id, and a "Sign in with Apple" key: its id and the .p8
     * private key (PEM). The client secret is a JWT signed with that key, generated here and renewed before it expires
     * ({@code clientSecretTtl}, at most 180 days per Apple).
     */
    public record Apple(
            @Nullable String clientId,
            @Nullable String teamId,
            @Nullable String keyId,
            @Nullable String privateKey,
            @DefaultValue("30d") Duration clientSecretTtl,

            @DefaultValue("https://appleid.apple.com/auth/authorize")
            String authorizationUri,

            @DefaultValue("https://appleid.apple.com/auth/token")
            String tokenUri,

            @DefaultValue("https://appleid.apple.com/auth/keys")
            String jwkSetUri,

            @DefaultValue("https://appleid.apple.com") String issuer) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank();
        }
    }
}
