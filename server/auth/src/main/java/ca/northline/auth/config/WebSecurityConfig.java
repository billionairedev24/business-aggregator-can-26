package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
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
    SecurityFilterChain web(
            HttpSecurity http,
            AuthProperties props,
            ObjectProvider<ClientRegistrationRepository> federation,
            FederatedSignIn federatedSignIn) {
        http.authorizeHttpRequests(a -> a.requestMatchers("/api/auth/**", "/actuator/health/**", "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .cors(c -> c.configurationSource(cors(props)))
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheck(Set.copyOf(props.allowedOrigins())), CsrfFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        if (federation.getIfAvailable() != null) {
            http.oauth2Login(o -> o.successHandler(federatedSignIn).failureHandler(federatedSignIn));
        }
        return http.build();
    }

    private static CorsConfigurationSource cors(AuthProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/auth/**", config);
        return source;
    }

    /** Rejects state-changing JSON API calls from browser origins that aren't allow-listed. */
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
            if (origin != null && !allowed.contains(origin) && !origin.equals(ownOrigin(request))) {
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
