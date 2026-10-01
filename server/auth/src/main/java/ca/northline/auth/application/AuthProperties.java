package ca.northline.auth.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.*}.
 *
 * @param termsVersion version of 09 Terms of Service / 10 Privacy Policy stored as {@code identity.users.terms_version}
 * @param otpTtl how long a phone code works
 * @param otpResendAfter resend cool-down (validation-rules.md: 45 s)
 * @param otpMaxAttempts wrong guesses before a new code is needed
 * @param totpKey base64 AES-256 key that encrypts {@code auth.totp_secrets.secret_enc}
 * @param totpIssuer issuer shown in authenticator apps
 * @param allowedOrigins browser origins that may call the JSON API (CORS + Origin check), e.g. the Studio
 * @param loginPage where an unauthenticated {@code /oauth2/authorize} is sent (the Studio's own sign-in page)
 * @param webauthn relying party
 * @param trustedProxies CIDRs of the load balancers / ingress allowed to set {@code X-Forwarded-*} (client IP for rate
 *     limits and the sign-in log); anyone else's forwarded headers are ignored
 * @param clientCityHeader request header in which the load balancer / CDN puts the client's city (for example
 *     {@code CloudFront-Viewer-City}, or a Google Cloud / Azure Front Door custom header); read only from trusted
 *     proxies, shown in Settings › Security's session list (S-19). Empty = no city.
 * @param consumerLoginPage S-62: the consumer web app's sign-in page — where an unauthenticated authorization request of
 *     a {@code consumerClients} client, and a Google / Apple sign-in started there, land (empty = {@code loginPage})
 * @param consumerClients S-62: OAuth clients whose people sign in on the consumer site ({@code consumer-bff})
 * @param mfaRequiredClients S-62: OAuth clients that get a code only for a sign-in with a second factor (the Studio's and
 *     the console's BFFs) — a consumer's phone-code sign-in is sent to their sign-in page instead
 * @param tokenEndpointOrigins S-139: browser origins whose public clients call {@code /oauth2/token} and
 *     {@code /oauth2/revoke} themselves (CORS): the api's Swagger UI and Scalar ({@code docs} client) outside prod.
 *     Empty = no CORS there (the BFFs and the apps call it server-side or natively).
 */
// platformZone: the zone account dates ("member since") are shown in — an account belongs to no market
// (REGION_PLATFORM_ZONE, region configuration; S-134)
@ConfigurationProperties("northline.auth")
public record AuthProperties(
        @DefaultValue("3.0") String termsVersion,
        @DefaultValue("10m") Duration otpTtl,
        @DefaultValue("45s") Duration otpResendAfter,
        @DefaultValue("5") int otpMaxAttempts,
        String totpKey,
        @DefaultValue("Northline") String totpIssuer,
        List<String> allowedOrigins,
        String loginPage,
        WebAuthn webauthn,

        @DefaultValue({"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
        List<String> trustedProxies,

        @Nullable String clientCityHeader,
        @Nullable String consumerLoginPage,
        @DefaultValue("consumer-bff") List<String> consumerClients,
        @DefaultValue({"studio-bff", "console-bff"}) List<String> mfaRequiredClients,
        ZoneId platformZone,
        @DefaultValue List<String> tokenEndpointOrigins) {

    /** WebAuthn relying party: id (registrable domain) and the origins allowed in client data. */
    public record WebAuthn(
            String rpId, @DefaultValue("Northline") String rpName, List<String> origins) {}
}
