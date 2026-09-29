package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.UserClaimsService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Spring Authorization Server: endpoints, JDBC storage (V017 {@code auth.oauth2_*}), ES256 signing and the token
 * claims ({@link UserClaimsService}). An unauthenticated {@code /oauth2/authorize} goes to the Studio's own sign-in page
 * ({@code northline.auth.login-page}); after the Studio's JSON sign-in the session exists and the code is issued without
 * any page (clients don't require consent).
 */
@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfig {

    /** Audience the api's resource server expects ({@code spring.security.oauth2.resourceserver.jwt.audiences}). */
    static final String API_AUDIENCE = "northline-api";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http, AuthProperties props) {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        return http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, as -> as.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
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

    @Bean
    OAuth2AuthorizationService authorizations(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * ES256 signing key (ARCHITECTURE.md § Identity). Generated per start-up — fine for one local instance; production
     * loads the key pair from the secrets manager (see DECISIONS.md).
     */
    @Bean
    JWKSource<SecurityContext> jwkSource() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var key = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
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
            }
        };
    }
}
