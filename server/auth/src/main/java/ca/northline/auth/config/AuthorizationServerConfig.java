package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.RefreshTokenReuse;
import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.dpop.AppClientAuthentication;
import ca.northline.auth.dpop.DpopTokens;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Spring Authorization Server: endpoints, JDBC storage (V017 {@code auth.oauth2_*}) and the token claims
 * ({@link UserClaimsService}). Tokens are signed through {@code ca.northline.auth.signing} (the {@code JwtEncoder} and
 * {@code JWKSource} beans: persistent keys in a file or a cloud KMS, with rotation). An unauthenticated {@code /oauth2/authorize} goes to the Studio's own sign-in page
 * ({@code northline.auth.login-page}); after the Studio's JSON sign-in the session exists and the code is issued without
 * any page (clients don't require consent).
 *
 * <p>S-29 (mobile and courier apps): DPoP-bound tokens ({@code cnf.jkt}), refresh tokens for DPoP public clients and
 * their refresh grant ({@code ca.northline.auth.dpop}), and refresh-token reuse detection
 * ({@link RefreshTokenReuseDetection}).
 */
@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfig {

    /** Audience the api's resource server expects ({@code spring.security.oauth2.resourceserver.jwt.audiences}). */
    static final String API_AUDIENCE = "northline-api";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(
            HttpSecurity http, AuthProperties props, RegisteredClientRepository clients) {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        return http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(
                        authorizationServer,
                        as -> as.oidc(Customizer.withDefaults())
                                // S-29: the mobile apps refresh (their DPoP key is the proof) and sign out
                                // (/oauth2/revoke) without a secret.
                                .clientAuthentication(c -> c.authenticationConverters(
                                                l -> l.addFirst(AppClientAuthentication.converter()))
                                        .authenticationProviders(
                                                l -> l.addFirst(AppClientAuthentication.provider(clients)))))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .headers(WebSecurityConfig::lockedDown)
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(props.loginPage()),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    RegisteredClientRepository clients(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    /**
     * JDBC storage, with each authorization linked to the session (sign-in) it came from (S-19) and refresh-token reuse
     * detection for public clients (S-29).
     */
    @Bean
    OAuth2AuthorizationService authorizations(
            JdbcOperations jdbc, RegisteredClientRepository clients, SessionService sessions, RefreshTokenReuse reuse) {
        return new SessionLinkedAuthorizations(
                new RefreshTokenReuseDetection(new JdbcOAuth2AuthorizationService(jdbc, clients), clients, reuse),
                sessions);
    }

    /**
     * What Spring Authorization Server would build itself (JWTs through our {@code JwtEncoder}, opaque access tokens,
     * refresh tokens), except that public DPoP clients get refresh tokens ({@link DpopTokens#refreshTokens}). Declaring
     * the generator turns off Spring's default claims customizer, so {@code cnf.jkt} is added here
     * ({@link DpopTokens#confirmation}) before our own claims.
     */
    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(
            JwtEncoder encoder, OAuth2TokenCustomizer<JwtEncodingContext> tokenClaims, Clock clock) {
        var jwt = new JwtGenerator(encoder);
        jwt.setJwtCustomizer(ctx -> {
            DpopTokens.confirmation(ctx);
            tokenClaims.customize(ctx);
        });
        return new DelegatingOAuth2TokenGenerator(
                jwt, new OAuth2AccessTokenGenerator(), DpopTokens.refreshTokens(clock));
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /** Validates our own tokens at the OIDC userinfo endpoint. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwks) {
        return NimbusJwtDecoder.withJwkSource(jwks)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
    }

    /**
     * Every token is ES256. User tokens get {@code roles}, {@code merchants}, {@code acr=mfa} (only after a second
     * factor) and {@code amr}; access tokens are also addressed to the api; ID tokens carry the profile.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenClaims(UserClaimsService claims) {
        return ctx -> {
            ctx.getJwsHeader().algorithm(SignatureAlgorithm.ES256);
            if (!(ctx.getPrincipal() instanceof UsernamePasswordAuthenticationToken user)) {
                return; // client_credentials (partners): no user claims
            }
            var factors = UserClaimsService.factorsOf(user);
            if (OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) {
                var audience = new ArrayList<>(List.of(ctx.getRegisteredClient().getClientId()));
                audience.add(API_AUDIENCE);
                ctx.getClaims().audience(audience);
                // RFC 9068: space-delimited string (the api's NorthlineJwtConverter reads it that way).
                ctx.getClaims().claim("scope", String.join(" ", ctx.getAuthorizedScopes()));
                ctx.getClaims().claims(c -> c.putAll(claims.accessTokenClaims(user.getName(), factors)));
            } else if (OidcParameterNames.ID_TOKEN.equals(ctx.getTokenType().getValue())) {
                ctx.getClaims().claims(c -> c.putAll(claims.idTokenClaims(user.getName(), factors)));
                // OIDC `sid`: the session (sign-in) the tokens belong to — the BFF shows it as the current session.
                SessionAuthentication.sessionIdOf(user)
                        .ifPresent(sid -> ctx.getClaims().claim("sid", sid));
            }
        };
    }
}
