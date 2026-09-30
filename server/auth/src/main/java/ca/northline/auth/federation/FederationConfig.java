package ca.northline.auth.federation;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.FederatedSignInService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * Google / Apple sign-in wiring (S-18). Only configured providers are registered; with none, {@code oauth2Login} is
 * off and {@link UnavailableProviderController} answers the buttons.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FederationProperties.class)
public class FederationConfig {

    static final String AUTHORIZATION_BASE = "/oauth2/authorization";

    @Bean
    FederationRegistrations federationRegistrations(FederationProperties props, Clock clock) {
        var apple = props.apple().enabled() ? new AppleClientSecret(props.apple(), clock) : null;
        var registrations = new FederationRegistrations(props, apple);
        log.info(
                "Federated sign-in (S-18): google={} apple={}",
                props.google().enabled() ? "on" : "off",
                props.apple().enabled() ? "on" : "off");
        return registrations;
    }

    /**
     * ID tokens: signature from the provider's JWK set, {@code aud} = our client id, nonce, expiry (Spring's
     * {@link OidcIdTokenValidator}), and {@code iss} among the provider's issuers — Google uses two spellings, so its
     * registration has no single issuer URI and is checked here.
     */
    @Bean
    JwtDecoderFactory<ClientRegistration> federationIdTokenDecoderFactory(FederationProperties props) {
        var factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> new DelegatingOAuth2TokenValidator<>(
                new OidcIdTokenValidator(registration), (Jwt jwt) -> issuerOk(props, registration, jwt)));
        return factory;
    }

    private static OAuth2TokenValidatorResult issuerOk(
            FederationProperties props, ClientRegistration registration, Jwt jwt) {
        var issuer = jwt.getClaimAsString("iss");
        List<String> allowed = switch (registration.getRegistrationId()) {
            case FederationRegistrations.GOOGLE -> props.google().issuers();
            case FederationRegistrations.APPLE -> List.of(props.apple().issuer());
            default -> List.of();
        };
        return issuer != null && allowed.contains(issuer)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_id_token", "Unexpected issuer " + issuer, null));
    }

    @Bean
    JdbcAuthorizationRequestRepository federationRequests(JdbcClient jdbc, Clock clock) {
        return new JdbcAuthorizationRequestRepository(jdbc, clock);
    }

    @Bean
    FederationLogin federationLogin(
            FederationRegistrations registrations,
            JdbcAuthorizationRequestRepository requests,
            FederatedSignInService federation,
            AuthProperties props) {
        return new FederationLogin(registrations, requests, new FederatedSignIn(federation, props));
    }

    /** Applies {@code oauth2Login} to the web security chain when at least one provider is configured. */
    public static final class FederationLogin {
        private final FederationRegistrations registrations;
        private final JdbcAuthorizationRequestRepository requests;
        private final FederatedSignIn handler;

        FederationLogin(
                FederationRegistrations registrations,
                JdbcAuthorizationRequestRepository requests,
                FederatedSignIn handler) {
            this.registrations = registrations;
            this.requests = requests;
            this.handler = handler;
        }

        public void configure(HttpSecurity http) {
            if (registrations.isEmpty()) {
                return;
            }
            http.oauth2Login(o -> o.clientRegistrationRepository(registrations)
                    .authorizationEndpoint(a -> a.authorizationRequestRepository(requests)
                            .authorizationRequestResolver(new KnownProvidersOnly(registrations)))
                    .successHandler(handler)
                    .failureHandler(handler));
        }
    }

    /** An unconfigured provider's button falls through to {@link UnavailableProviderController} instead of a 500. */
    static final class KnownProvidersOnly implements OAuth2AuthorizationRequestResolver {
        private final FederationRegistrations registrations;
        private final DefaultOAuth2AuthorizationRequestResolver delegate;

        KnownProvidersOnly(FederationRegistrations registrations) {
            this.registrations = registrations;
            this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE);
        }

        @Override
        public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            var path =
                    request.getRequestURI().substring(request.getContextPath().length());
            if (!path.startsWith(AUTHORIZATION_BASE + "/")) {
                return null;
            }
            var id = path.substring(AUTHORIZATION_BASE.length() + 1);
            return registrations.findByRegistrationId(id) == null ? null : delegate.resolve(request);
        }

        @Override
        public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
            return registrations.findByRegistrationId(registrationId) == null
                    ? null
                    : delegate.resolve(request, registrationId);
        }
    }
}
