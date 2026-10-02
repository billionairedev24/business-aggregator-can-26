package ca.northline.config;

import ca.northline.mcp.DevDocsProperties;
import ca.northline.mcp.McpProperties;
import ca.northline.mcp.McpSecurityConfiguration;
import ca.northline.shared.security.Authorities;
import jakarta.servlet.DispatcherType;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resource server: validates JWTs from northline-auth (claims mapped by {@link NorthlineJwtConverter}).
 * Merchant membership + {@code acr=mfa} are enforced per handler by {@code @RequiresMerchant}; this chain only does
 * the coarse, path-level rules — including staff role + {@code acr=mfa} for {@code /api/v1/console/**}. Under the {@code local} profile {@link DevAuthFilter} may authenticate first.
 * DPoP-bound tokens of the mobile apps (S-29) are accepted with a proof of their key only ({@link DpopResourceConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * S-127: the MCP server (Streamable HTTP at {@code /mcp}) and its OAuth 2.0 Protected Resource Metadata (RFC 9728).
     * A bearer token is required (the MCP gateway then checks its audience, second factor and scopes); a missing or
     * invalid one is {@code 401} with {@code resource_metadata} and the scopes to ask for. Dev auth under {@code local}.
     */
    @Bean
    @Order(0)
    SecurityFilterChain mcp(
            HttpSecurity http,
            NorthlineJwtConverter converter,
            ObjectProvider<DevAuthFilter> devAuth,
            McpProperties mcp,
            DevDocsProperties docs,
            JsonMapper json) {
        devAuth.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        var entryPoint = McpSecurityConfiguration.entryPoint(mcp, docs);
        return http.securityMatcher(
                        "/mcp",
                        "/mcp/**",
                        McpSecurityConfiguration.METADATA_PATH,
                        McpSecurityConfiguration.METADATA_PATH + "/**")
                // first in the chain: Spring Security's own resource metadata would answer without the authorization
                // server
                .addFilterBefore(
                        McpSecurityConfiguration.metadataEndpoint(mcp, docs, json), DisableEncodeUrlFilter.class)
                .authorizeHttpRequests(a -> {
                    a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                            .permitAll()
                            .requestMatchers(
                                    McpSecurityConfiguration.METADATA_PATH,
                                    McpSecurityConfiguration.METADATA_PATH + "/**")
                            .permitAll();
                    // S-128: the developer docs server — open locally; in the cloud a staff token (DevDocsAccessFilter)
                    if (docs.access() == DevDocsProperties.Access.OPEN) {
                        a.requestMatchers("/mcp/docs").permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(
                        o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(
            HttpSecurity http,
            NorthlineJwtConverter converter,
            ObjectProvider<DevAuthFilter> devAuth,
            JwtDecoderFactory<DPoPProofContext> dpopProofs) {
        devAuth.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.requestMatchers(
                                "/api/v1/search/**", "/api/v1/storefronts/**", "/api/v1/geo/**")
                        .permitAll()
                        // The consumer cart (S-51): guests have one too, keyed by the consumer-bff's guest id
                        .requestMatchers("/api/v1/cart", "/api/v1/cart/**")
                        .permitAll()
                        // Public reads for the consumer app, e.g. the page a merchant's own domain serves (S-31)
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**")
                        .permitAll()
                        // S-75: a public page reports a visit (a counter; no identity, nothing else stored)
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/storefronts/*/visits")
                        .permitAll()
                        // Email unsubscribe links: the signed token is the authorisation (S-13). The template
                        // previews and the fake Stripe Identity page (S-22) exist only under `local` (404 elsewhere).
                        .requestMatchers(
                                "/api/v1/email/unsubscribe",
                                "/api/v1/dev/emails/**",
                                "/api/v1/dev/identity-sessions/**")
                        .permitAll()
                        // Stripe webhooks: authenticated by the Stripe-Signature, not a token (S-12)
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe", "/api/v1/webhooks/stripe/connect")
                        .permitAll()
                        // Calendar change notifications: verified per channel secret, not a token (S-32)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/webhooks/calendar/google",
                                "/api/v1/webhooks/calendar/microsoft",
                                "/api/v1/webhooks/calendar/microsoft/lifecycle")
                        .permitAll()
                        // Shopify / Square / Lightspeed (S-35): webhooks verified by the platform's HMAC; the OAuth
                        // redirect URI is authenticated by its single-use state (and Shopify's hmac)
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/commerce/*")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/commerce/oauth/*/callback")
                        .permitAll()
                        // Staff tokens require acr=mfa like business ones (CLAUDE.md; S-20 found only the role checked)
                        .requestMatchers("/api/v1/console/**")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("STAFF"),
                                AuthorityAuthorizationManager.hasAuthority(Authorities.MFA)))
                        // S-86: the courier app — scope courier on a DPoP-bound token (S-29: cnf.jkt, so the proof was
                        // checked; a bearer token never reaches the courier's run, stops or proof uploads)
                        .requestMatchers("/api/v1/courier/**")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("SCOPE_courier"),
                                (auth, _) -> new AuthorizationDecision(dpopBound(auth.get()))))
                        // S-102: the push device registry is the apps' own (a DPoP-bound token of the consumer or
                        // courier app); a browser session's or a partner's bearer token has no device to register
                        .requestMatchers("/api/v1/me/devices", "/api/v1/me/devices/**")
                        .access((auth, _) -> new AuthorizationDecision(dpopBound(auth.get())))
                        // Studio tokens (scope merchant) and partner clients (S-30: role partner — each handler
                        // must also be marked @PartnerAccess, and only the partner's businesses are open)
                        .requestMatchers("/api/v1/merchants/**")
                        .hasAnyAuthority("SCOPE_merchant", Authorities.PARTNER)
                        // Partner tokens reach nothing else (no person behind them: /me, orders, …)
                        .anyRequest()
                        .access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                AuthorizationManagers.not(
                                        AuthorityAuthorizationManager.hasAuthority(Authorities.PARTNER)))))
                // S-29: `Authorization: DPoP <token>` + `DPoP: <proof>` (mobile apps). Spring's bearer filter refuses a
                // DPoP-bound token (cnf.jkt) sent as a bearer token, so a stolen one is useless without the app's key.
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .dPoP(Customizer.withDefaults())
                        .withObjectPostProcessor(new ObjectPostProcessor<DPoPAuthenticationProvider>() {
                            @Override
                            public <O extends DPoPAuthenticationProvider> O postProcess(O provider) {
                                provider.setDPoPProofVerifierFactory(dpopProofs);
                                return provider;
                            }
                        }))
                .exceptionHandling(e -> e.accessDeniedHandler(problemDenied()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /** Actuator health/info and the OpenAPI docs are public; anything else outside /api is closed. */
    @Bean
    @Order(2)
    SecurityFilterChain infrastructure(HttpSecurity http, @Value("${northline.docs.enabled:true}") boolean docsPublic) {
        return http.authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                    // S-125/S-127: prod keeps springdoc's model for the MCP tools but doesn't publish it
                    if (docsPublic) {
                        a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    a.anyRequest().denyAll();
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Filter-level 403s (e.g. missing {@code merchant} scope) in the same ProblemDetail shape as the controller advice.
     * Staff without a second factor get {@code mfa_required}, like merchant endpoints, so the client knows to step up.
     */
    private static AccessDeniedHandler problemDenied() {
        return (request, response, _) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            if (request.getRequestURI().startsWith("/api/v1/console/") && staffWithoutMfa()) {
                response.getWriter().write("""
                        {"type":"https://northline.ca/problems/mfa-required","title":"Forbidden","status":403,\
                        "detail":"Sign in with your second factor to do this.","code":"mfa_required"}""");
                return;
            }
            response.getWriter().write("""
                    {"type":"https://northline.ca/problems/forbidden","title":"Forbidden","status":403,\
                    "detail":"Your sign-in doesn't allow this.","code":"forbidden"}""");
        };
    }

    /** A token bound to the app's key ({@code cnf.jkt}); Spring's DPoP filter has verified the proof for it. */
    static boolean dpopBound(@Nullable Authentication auth) {
        return auth instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaims().get("cnf") instanceof Map<?, ?> cnf
                && cnf.get("jkt") instanceof String jkt
                && !jkt.isBlank();
    }

    private static boolean staffWithoutMfa() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        var authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return authorities.contains(Authorities.ROLE_PREFIX + "STAFF") && !authorities.contains(Authorities.MFA);
    }
}
