package ca.northline.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * studio-bff security: OAuth2 login (authorization code + PKCE) against northline-auth, a server-side session (HttpOnly
 * cookie {@code NL_STUDIO}, {@code __Host-NL_STUDIO} in the cloud), CSRF double-submit with cookie {@code XSRF-TOKEN}
 * ({@code __Host-XSRF-TOKEN} in the cloud) / header {@code X-XSRF-TOKEN}, 401 (not a redirect) for unauthenticated XHR,
 * {@code POST /bff/logout} → 204, and the S-19 revocation check ({@link SessionRevocationCheck}).
 *
 * <p>S-45: under the {@code consumer} profile ({@code northline.bff.guests}) {@code /api/**} is open to guests too — the
 * relay then goes without a token and the api decides (public endpoints answer, the rest 401). CSRF still applies to
 * every guest's POST.
 *
 * <p>S-20: the CSRF token is accepted from the header only — never from a {@code _csrf} form field, which a page on a
 * sibling subdomain (same site, so {@code SameSite=Lax} doesn't stop it) could submit after planting its own cookie —
 * and every response forbids framing and carries a CSP that allows nothing (the BFF serves no pages).
 *
 * <p>S-90: under the {@code console} profile ({@code northline.bff.staff-only}) only Northline staff who signed in with a
 * second factor keep a session ({@link StaffGate}).
 */
@Configuration(proxyBeanMethods = false)
class BffSecurityConfig {

    @Bean
    SecurityFilterChain bff(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            NextRedirect next,
            RevokeTokensOnLogout revoke,
            BffProperties props,
            OAuth2AuthorizedClientRepository authorizedClients,
            TokenIntrospection introspection,
            @Value("${server.servlet.session.cookie.name:NL_STUDIO}") String sessionCookie) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        // S-90 console-bff: staff with a second factor only (StaffGate); everyone else goes on to `next`.
        var signedIn = props.staffOnly() ? StaffGate.signIn(next, revoke, props) : next;
        if (props.staffOnly()) {
            http.addFilterBefore(StaffGate.filter(revoke), AuthorizationFilter.class);
        }
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return http.authorizeHttpRequests(a -> {
                    a.requestMatchers("/bff/session", "/bff/login", "/actuator/health/**", "/error")
                            .permitAll();
                    if (props.guests()) {
                        a.requestMatchers("/api/**").permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .oauth2Login(o -> o.authorizationEndpoint(ae -> ae.authorizationRequestResolver(resolver))
                        .successHandler(signedIn)
                        .failureHandler(
                                (request, response, _) -> response.sendRedirect(props.signInPage() + "?error=signin")))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(c -> c.csrfTokenRepository(csrfCookie(props.csrfCookieName()))
                        .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(
                        new SessionRevocationCheck(authorizedClients, introspection, props, Clock.systemUTC()),
                        AuthorizationFilter.class)
                .logout(l -> l.logoutUrl("/bff/logout")
                        .addLogoutHandler(revoke)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies(sessionCookie)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .build();
    }

    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * Readable by the Studio (not HttpOnly), {@code SameSite=Strict}, path {@code /}; a {@code __Host-} name is always
     * {@code Secure}, as browsers require (it has no Domain attribute, so it stays on the Studio host).
     */
    static CookieCsrfTokenRepository csrfCookie(String name) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(name);
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Strict").path("/");
            if (name.startsWith("__Host-")) {
                cookie.secure(true);
            }
        });
        return repository;
    }

    /** The raw token from {@code X-XSRF-TOKEN}; the {@code _csrf} request parameter is ignored (S-20). */
    static final class HeaderOnlyCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        @Override
        public @Nullable String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            return request.getHeader(csrfToken.getHeaderName());
        }
    }

    /** northline-auth signs ID tokens with ES256 (ARCHITECTURE.md § Identity). */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        var factory = new OidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(_ -> SignatureAlgorithm.ES256);
        return factory;
    }

    /**
     * Tokens live in the HTTP session (Valkey in the cloud), as DECISIONS.md describes — not in Boot's default in-memory
     * store, which only one replica would see and which outlived an invalidated session (S-19).
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
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
