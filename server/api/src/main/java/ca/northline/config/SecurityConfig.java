package ca.northline.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Resource server: validates JWTs from northline-auth (claims mapped by {@link NorthlineJwtConverter}).
 * Merchant membership + {@code acr=mfa} are enforced per handler by {@code @RequiresMerchant}; this chain only does
 * the coarse, path-level rules. Under the {@code local} profile {@link DevAuthFilter} may authenticate first.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, NorthlineJwtConverter converter, ObjectProvider<DevAuthFilter> devAuth) {
        devAuth.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.securityMatcher("/api/**")
                .authorizeHttpRequests(
                        a -> a.requestMatchers("/api/v1/search/**", "/api/v1/storefronts/**", "/api/v1/geo/**")
                                .permitAll()
                                // Email unsubscribe links: the signed token is the authorisation (S-13). The template
                                // previews exist only under the `local` profile (404 elsewhere).
                                .requestMatchers("/api/v1/email/unsubscribe", "/api/v1/dev/emails/**")
                                .permitAll()
                                .requestMatchers("/api/v1/console/**")
                                .hasRole("STAFF")
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

    /** Filter-level 403s (e.g. missing {@code merchant} scope) in the same ProblemDetail shape as the controller advice. */
    private static AccessDeniedHandler problemDenied() {
        return (_, response, _) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://northline.ca/problems/forbidden","title":"Forbidden","status":403,\
                    "detail":"Your sign-in doesn't allow this.","code":"forbidden"}""");
        };
    }
}
