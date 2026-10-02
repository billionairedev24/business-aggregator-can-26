package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.LoginPages;
import ca.northline.auth.application.PartnerTokens;
import ca.northline.auth.application.RefreshTokenReuse;
import ca.northline.auth.application.SessionAuthentication;
import ca.northline.auth.application.SessionService;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.dpop.AppClientAuthentication;
import ca.northline.auth.dpop.DpopTokens;
import ca.northline.auth.mcp.ClientIdMetadataDocuments;
import ca.northline.auth.mcp.McpAuthProperties;
import ca.northline.auth.mcp.ResourceIndicators;
import ca.northline.auth.partners.PartnerAssertions;
import ca.northline.auth.partners.PartnerClaims;
import ca.northline.auth.partners.PartnerTokenErrors;
import ca.northline.auth.partners.PartnerTokenProvider;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
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
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
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
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Authorization Server: endpoints, JDBC storage (V017 {@code auth.oauth2_*}) and the token claims
 * ({@link UserClaimsService}). Tokens are signed through {@code ca.northline.auth.signing} (the {@code JwtEncoder} and
 * {@code JWKSource} beans: persistent keys in a file or a cloud KMS, with rotation). An unauthenticated {@code /oauth2/authorize} goes to its client's sign-in page
 * ({@code northline.auth.login-page}, or the consumer site's for consumer-bff — S-62, {@link LoginPages}); after the
 * JSON sign-in the session exists and the code is issued without any page (clients don't require consent). Business
 * clients get a code only for a sign-in with a second factor ({@link MfaRequiredClients}).
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
            HttpSecurity http,
            LoginPages pages,
            RegisteredClientRepository clients,
            PartnerAssertions partnerAssertions,
            PartnerTokens partnerTokens,
            ResourceIndicators resources,
            AuthProperties props) {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        return http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(
                        authorizationServer,
                        as -> as.oidc(o -> o.providerConfigurationEndpoint(p -> p.providerConfigurationCustomizer(
                                        b -> b.claim(CLIENT_ID_METADATA_DOCUMENTS, true))))
                                // S-127 (MCP): agents without a registration name themselves with an HTTPS client id
                                // (Client ID Metadata Documents); the resource they want a token for is checked
                                // (RFC 8707) before the person is asked anything.
                                .authorizationServerMetadataEndpoint(m -> m.authorizationServerMetadataCustomizer(
                                        b -> b.claim(CLIENT_ID_METADATA_DOCUMENTS, true)))
                                .authorizationEndpoint(a -> a.authenticationProviders(l -> l.forEach(p -> {
                                    if (p instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codes) {
                                        codes.setAuthenticationValidator(
                                                new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                                                        .andThen(c -> checkResource(c, resources)));
                                    }
                                })))
                                // S-29: the mobile apps refresh (their DPoP key is the proof) and sign out
                                // (/oauth2/revoke) without a secret.
                                .clientAuthentication(c -> c.authenticationConverters(
                                                l -> l.addFirst(AppClientAuthentication.converter()))
                                        .authenticationProviders(l -> {
                                            l.addFirst(AppClientAuthentication.provider(clients));
                                            // S-30: partners' private_key_jwt assertions (keys, lifetime, jti).
                                            l.forEach(p -> {
                                                if (p instanceof JwtClientAssertionAuthenticationProvider assertions) {
                                                    assertions.setJwtDecoderFactory(partnerAssertions);
                                                }
                                            });
                                        }))
                                // S-30: partner tokens are rate limited and audited; over the limit = 429.
                                .tokenEndpoint(t -> t.authenticationProviders(l -> l.replaceAll(
                                                p -> p instanceof OAuth2ClientCredentialsAuthenticationProvider
                                                        ? new PartnerTokenProvider(p, partnerTokens)
                                                        : p))
                                        .errorResponseHandler(new PartnerTokenErrors())))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                // S-139: the API viewers (public client `docs`) exchange their code from the browser
                .cors(c -> c.configurationSource(tokenEndpointCors(props.tokenEndpointOrigins())))
                .headers(WebSecurityConfig::lockedDown)
                // S-62: each client's people sign in on their own site (the Studio's, the consumer's).
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        (request, response, ex) -> new LoginUrlAuthenticationEntryPoint(
                                        pages.forClient(request.getParameter(OAuth2ParameterNames.CLIENT_ID)))
                                .commence(request, response, ex),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // S-62: no Studio / console code for a sign-in without a second factor.
                .addFilterBefore(new MfaRequiredClients(pages), AbstractPreAuthenticatedProcessingFilter.class)
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * S-139: CORS for the token and revocation endpoints, for the browser origins of public clients that call them
     * from a page ({@code northline.auth.token-endpoint-origins}: the api's Swagger UI and Scalar outside prod). No
     * credentials: PKCE public clients send no cookie and no secret. Nothing is registered when the list is empty. Used
     * by this chain (the POST) and by the web chain, which receives the preflight: this chain only matches
     * {@code POST /oauth2/token}.
     */
    static CorsConfigurationSource tokenEndpointCors(List<String> origins) {
        var source = new UrlBasedCorsConfigurationSource();
        registerTokenEndpointCors(source, origins);
        return source;
    }

    static void registerTokenEndpointCors(UrlBasedCorsConfigurationSource source, List<String> origins) {
        if (!origins.isEmpty()) {
            var config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("POST"));
            config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
            config.setAllowCredentials(false);
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/oauth2/token", config);
            source.registerCorsConfiguration("/oauth2/revoke", config);
        }
    }

    /**
     * The configured clients (S-122) in the database, plus S-127's agents that identify themselves with a Client ID
     * Metadata Document ({@code client_id} = an HTTPS URL).
     */
    @Bean
    RegisteredClientRepository clients(JdbcOperations jdbc, McpAuthProperties mcp, JsonMapper json, Clock clock) {
        return new ClientIdMetadataDocuments(
                new JdbcRegisteredClientRepository(jdbc), mcp.metadataDocuments(), json, clock);
    }

    /** Authorization server metadata (RFC 8414 / OIDC discovery) field of the Client ID Metadata Document draft. */
    static final String CLIENT_ID_METADATA_DOCUMENTS = "client_id_metadata_document_supported";

    /** RFC 8707 at the authorization endpoint: an unknown resource is refused there, before sign-in or consent. */
    private static void checkResource(
            OAuth2AuthorizationCodeRequestAuthenticationContext context, ResourceIndicators resources) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        try {
            resources.requested(request.getAdditionalParameters());
        } catch (OAuth2AuthenticationException e) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(e.getError(), request);
        }
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
    OAuth2TokenCustomizer<JwtEncodingContext> tokenClaims(UserClaimsService claims, ResourceIndicators resources) {
        return ctx -> {
            ctx.getJwsHeader().algorithm(SignatureAlgorithm.ES256);
            if (!(ctx.getPrincipal() instanceof UsernamePasswordAuthenticationToken user)) {
                PartnerClaims.add(ctx, API_AUDIENCE); // client_credentials (partners, S-30): no user claims
                if (OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) {
                    // S-127: a partner may ask for a token for the MCP server too (resource indicator)
                    var audience = ctx.getClaims().build().getAudience();
                    resources.addAudience(ctx, audience == null ? List.of(API_AUDIENCE) : audience);
                }
                return;
            }
            claims.requireNotErased(user.getName()); // S-105
            var factors = UserClaimsService.factorsOf(user);
            if (OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) {
                var audience = new ArrayList<>(List.of(ctx.getRegisteredClient().getClientId()));
                audience.add(API_AUDIENCE);
                ctx.getClaims().audience(audience);
                resources.addAudience(ctx, audience); // S-127: + the MCP server when the client asked for it
                // RFC 9068: space-delimited string (the api's NorthlineJwtConverter reads it that way).
                ctx.getClaims().claim("scope", String.join(" ", ctx.getAuthorizedScopes()));
                // RFC 9068 § 2.2: which client the token was issued to (the MCP server audits it per tool call, S-127).
                ctx.getClaims().claim("client_id", ctx.getRegisteredClient().getClientId());
                ctx.getClaims().claims(c -> c.putAll(claims.accessTokenClaims(user.getName(), factors)));
            } else if (OidcParameterNames.ID_TOKEN.equals(ctx.getTokenType().getValue())) {
                ctx.getClaims()
                        .claims(c ->
                                c.putAll(claims.idTokenClaims(user.getName(), factors, ctx.getAuthorizedScopes())));
                // OIDC `sid`: the session (sign-in) the tokens belong to — the BFF shows it as the current session.
                SessionAuthentication.sessionIdOf(user)
                        .ifPresent(sid -> ctx.getClaims().claim("sid", sid));
            }
        };
    }
}
