package ca.northline.auth.federation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * The Google and Apple client registrations that are configured (S-18) — none, one or both. Apple's registration is
 * rebuilt on every lookup with the current {@link AppleClientSecret}, so a renewed secret is used without a restart.
 * Redirect URIs are {@code <public auth URL>/login/oauth2/code/<google|apple>}.
 */
public final class FederationRegistrations implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    public static final String GOOGLE = "google";
    public static final String APPLE = "apple";
    /**
     * The public auth URL as the request shows it (TrustedProxyFilter applies X-Forwarded-Proto/Host from the ingress):
     * {@code https://auth.northline.ca/login/oauth2/code/google} — what the provider consoles must list.
     */
    static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    private final Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
    private final @Nullable AppleClientSecret appleSecret;

    FederationRegistrations(FederationProperties props, @Nullable AppleClientSecret appleSecret) {
        this.appleSecret = appleSecret;
        var google = props.google();
        if (google.enabled()) {
            registrations.put(
                    GOOGLE,
                    ClientRegistration.withRegistrationId(GOOGLE)
                            .clientName("Google")
                            .clientId(Objects.requireNonNull(google.clientId()).strip())
                            .clientSecret(Objects.requireNonNullElse(google.clientSecret(), "")
                                    .strip())
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri(REDIRECT_URI)
                            .scope("openid", "email", "profile")
                            .authorizationUri(google.authorizationUri())
                            .tokenUri(google.tokenUri())
                            .jwkSetUri(google.jwkSetUri())
                            .userInfoUri(google.userInfoUri())
                            .userNameAttributeName(IdTokenClaimNames.SUB)
                            .build());
        }
        var apple = props.apple();
        if (apple.enabled() && appleSecret != null) {
            registrations.put(
                    APPLE,
                    ClientRegistration.withRegistrationId(APPLE)
                            .clientName("Apple")
                            .clientId(Objects.requireNonNull(apple.clientId()).strip())
                            .clientSecret("generated-per-request")
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri(REDIRECT_URI)
                            .scope("openid", "email", "name")
                            // Apple needs form_post when name/email are requested (the answer is a cross-site POST).
                            .authorizationUri(apple.authorizationUri() + "?response_mode=form_post")
                            .tokenUri(apple.tokenUri())
                            .jwkSetUri(apple.jwkSetUri())
                            .issuerUri(apple.issuer())
                            .userNameAttributeName(IdTokenClaimNames.SUB)
                            .build());
        }
    }

    @Override
    public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
        var registration = registrations.get(registrationId);
        if (registration == null || !APPLE.equals(registrationId) || appleSecret == null) {
            return registration;
        }
        return ClientRegistration.withClientRegistration(registration)
                .clientSecret(appleSecret.current())
                .build();
    }

    public boolean isEmpty() {
        return registrations.isEmpty();
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return registrations.values().iterator();
    }
}
