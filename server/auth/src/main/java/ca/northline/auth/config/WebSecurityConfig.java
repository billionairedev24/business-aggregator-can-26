package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.SessionService;
import ca.northline.auth.federation.FederationConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Everything that isn't an authorization-server endpoint: the Studio's JSON API ({@code /api/auth/**}), Google/Apple
 * federation and health.
 *
 * <p>The Studio calls the JSON API cross-origin (same site) with credentials, so it gets CORS for
 * {@code northline.auth.allowed-origins}. CSRF protection for it is an Origin allow-list on every state-changing request
 * plus JSON-only bodies (a cross-site form can't send {@code application/json} without a preflight); Spring's token
 * CSRF is off there because the token cookie would live on the auth host, unreadable from the Studio.
 */
@Configuration(proxyBeanMethods = false)
class WebSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain web(HttpSecurity http, AuthProperties props, FederationConfig.FederationLogin federation) {
        http.authorizeHttpRequests(a -> a.requestMatchers(
                                "/api/auth/**", "/actuator/health/**", "/error", "/oauth2/authorization/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .cors(c -> c.configurationSource(cors(props)))
                .headers(WebSecurityConfig::lockedDown)
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheck(Set.copyOf(props.allowedOrigins())), CsrfFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        federation.configure(http); // Google / Apple (S-18), when configured
        return http.build();
    }

    /**
     * S-20: nothing here is a page to render or frame — JSON, redirects and error bodies only — so on top of Spring
     * Security's defaults (nosniff, {@code X-Frame-Options: DENY}, no-store, HSTS over HTTPS) the CSP allows nothing.
     */
    static void lockedDown(HeadersConfigurer<HttpSecurity> headers) {
        headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
    }

    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /** Before everything (Spring Session, Spring Security): the rest of the app sees the client's address. */
    @Bean
    FilterRegistrationBean<TrustedProxyFilter> trustedProxyFilter(AuthProperties props) {
        var registration =
                new FilterRegistrationBean<>(new TrustedProxyFilter(props.trustedProxies(), props.clientCityHeader()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * After Spring Session (which wraps the request), before Spring Security (which reads the session's security
     * context): sessions whose sign-in ended are dropped here (S-19).
     */
    @Bean
    FilterRegistrationBean<RevokedSessionFilter> revokedSessionFilter(SessionService sessions) {
        var registration = new FilterRegistrationBean<>(new RevokedSessionFilter(sessions));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    private static CorsConfigurationSource cors(AuthProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/auth/**", config);
        return source;
    }

    /**
     * Rejects state-changing JSON API calls from browser origins that aren't allow-listed. A request without
     * {@code Origin} is let through (not a browser) unless its Fetch Metadata says a browser sent it cross-site (S-20).
     */
    static final class OriginCheck extends OncePerRequestFilter {
        private final Set<String> allowed;

        OriginCheck(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/auth/")
                    || HttpMethod.GET.matches(request.getMethod())
                    || HttpMethod.OPTIONS.matches(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var origin = request.getHeader("Origin");
            var refused = origin == null
                    ? "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    : !allowed.contains(origin) && !origin.equals(ownOrigin(request));
            if (refused) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("""
                        {"type":"https://northline.ca/problems/forbidden","title":"Forbidden","status":403,\
                        "detail":"Origin not allowed.","code":"forbidden"}""");
                return;
            }
            chain.doFilter(request, response);
        }

        private static String ownOrigin(HttpServletRequest request) {
            var port = request.getServerPort();
            var scheme = request.getScheme();
            var defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        }
    }
}
