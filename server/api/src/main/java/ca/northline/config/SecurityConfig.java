package ca.northline.config;

import ca.northline.shared.security.Authorities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Resource server: validates JWTs from northline-auth (claims mapped by {@link NorthlineJwtConverter}).
 * Merchant membership + {@code acr=mfa} are enforced per handler by {@code @RequiresMerchant}; this chain only does
 * the coarse, path-level rules — including staff role + {@code acr=mfa} for {@code /api/v1/console/**}. Under the {@code local} profile {@link DevAuthFilter} may authenticate first.
 * DPoP-bound tokens of the mobile apps (S-29) are accepted with a proof of their key only ({@link DpopResourceConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

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
                        // Staff tokens require acr=mfa like business ones (CLAUDE.md; S-20 found only the role checked)
                        .requestMatchers("/api/v1/console/**")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("STAFF"),
                                AuthorityAuthorizationManager.hasAuthority(Authorities.MFA)))
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
    SecurityFilterChain infrastructure(HttpSecurity http) {
        return http.authorizeHttpRequests(a -> a.requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
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
