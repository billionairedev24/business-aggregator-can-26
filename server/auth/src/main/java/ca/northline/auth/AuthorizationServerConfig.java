package ca.northline.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(as -> as.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain login(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/login", "/register", "/assets/**", "/webauthn/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(f -> f.loginPage("/login")) // email + magic link / password fallback
                .webAuthn(w -> w.rpName("Northline")
                        .rpId("northline.ca")
                        .allowedOrigins("https://northline.ca", "https://business.northline.ca"))
                .oauth2Login(o -> o.loginPage("/login")) // Google, Apple
                .build();
    }

    @Bean
    RegisteredClientRepository clients(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizations(JdbcOperations jdbc, RegisteredClientRepository c) {
        return new JdbcOAuth2AuthorizationService(jdbc, c);
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcOperations jdbc, RegisteredClientRepository c) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, c);
    }

    /** Adds roles, merchant memberships and acr (mfa) to access + id tokens. */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> claims(UserClaimsService users) {
        return ctx -> {
            var principal = ctx.getPrincipal();
            if (principal != null) {
                users.claimsFor(principal.getName())
                        .forEach((k, v) -> ctx.getClaims().claim(k, v));
            }
        };
    }
}
