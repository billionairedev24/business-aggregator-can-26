package ca.northline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Resource server: validates JWTs from northline-auth. Roles/merchant memberships mapped by NorthlineJwtConverter. */
@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    SecurityFilterChain api(HttpSecurity http, NorthlineJwtConverter converter) throws Exception {
        return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/search/**", "/api/v1/storefronts/**", "/api/v1/geo/**").permitAll()
                .requestMatchers("/api/v1/console/**").hasRole("STAFF")
                .requestMatchers("/api/v1/merchants/**").hasAuthority("SCOPE_merchant")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable())
            .build();
    }
}
