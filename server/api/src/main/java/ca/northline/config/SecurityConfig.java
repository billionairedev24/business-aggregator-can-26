package ca.northline.config;

import ca.northline.shared.security.Authorities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Resource server: validates JWTs from northline-auth (claims mapped by {@link NorthlineJwtConverter}).
 * Merchant membership + {@code acr=mfa} are enforced per handler by {@code @RequiresMerchant}; this chain only does
 * the coarse, path-level rules — including staff role + {@code acr=mfa} for {@code /api/v1/console/**}. Under the {@code local} profile {@link DevAuthFilter} may authenticate first.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, NorthlineJwtConverter converter, ObjectProvider<DevAuthFilter> devAuth) {
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
                        // Calendar change notifications: verified per channel secret, not a token (S-32)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/webhooks/calendar/google",
                                "/api/v1/webhooks/calendar/microsoft",
                                "/api/v1/webhooks/calendar/microsoft/lifecycle")
                        .permitAll()
                        // Staff tokens require acr=mfa like business ones (CLAUDE.md; S-20 found only the role checked)
                        .requestMatchers("/api/v1/console/**")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("STAFF"),
                                AuthorityAuthorizationManager.hasAuthority(Authorities.MFA)))
                        .requestMatchers("/api/v1/merchants/**")
                        .hasAuthority("SCOPE_merchant")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
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
