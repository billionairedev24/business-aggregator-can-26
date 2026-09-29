package ca.northline.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * studio-bff security: OAuth2 login (authorization code + PKCE) against northline-auth, a server-side session (HttpOnly
 * cookie {@code NL_STUDIO}), CSRF double-submit with cookie {@code XSRF-TOKEN} / header {@code X-XSRF-TOKEN}, 401 (not
 * a redirect) for unauthenticated XHR, and {@code POST /bff/logout} → 204.
 */
@Configuration(proxyBeanMethods = false)
class BffSecurityConfig {

    @Bean
    SecurityFilterChain bff(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            NextRedirect next,
            RevokeTokensOnLogout revoke,
            BffProperties props) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return http.authorizeHttpRequests(
                        a -> a.requestMatchers("/bff/session", "/bff/login", "/actuator/health/**", "/error")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2Login(o -> o.authorizationEndpoint(ae -> ae.authorizationRequestResolver(resolver))
                        .successHandler(next)
                        .failureHandler(
                                (request, response, _) -> response.sendRedirect(props.signInPage() + "?error=signin")))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(c -> c.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .logout(l -> l.logoutUrl("/bff/logout")
                        .addLogoutHandler(revoke)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("NL_STUDIO")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .build();
    }

    /** northline-auth signs ID tokens with ES256 (ARCHITECTURE.md § Identity). */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        var factory = new OidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(_ -> SignatureAlgorithm.ES256);
        return factory;
    }

    /** Used by the gateway's TokenRelay filter: refreshes the access token when it is about to expire. */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }

    /** Touches the deferred CSRF token so the {@code XSRF-TOKEN} cookie is written on the first response. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
