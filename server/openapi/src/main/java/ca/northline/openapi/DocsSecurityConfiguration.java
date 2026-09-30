package ca.northline.openapi;

import java.util.ArrayList;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The viewer and spec paths get their own filter chain, ahead of each app's: public (the specs describe, they don't
 * grant anything), stateless, and a CSP that is narrow but lets the viewers run — scripts, styles and fonts from the
 * app itself ('unsafe-inline' for styles and for the initialisers Swagger UI and Scalar write), XHR to the app and to
 * northline-auth (the "Authorize" / "Try it" token exchange), no frames, no forms elsewhere. Every other path of
 * northline-auth and the BFF keeps S-20's {@code default-src 'none'}. Absent in production
 * ({@code northline.docs.enabled=false}), where the paths answer 404 behind the apps' own chains.
 */
@AutoConfiguration(after = NorthlineOpenApiAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnProperty(prefix = "northline.docs", name = "enabled", matchIfMissing = true)
public class DocsSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain northlineDocs(HttpSecurity http, DocsProperties props, Environment environment) {
        return http.securityMatcher(paths(props, environment))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(csp(props)))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    static String csp(DocsProperties props) {
        return "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; font-src 'self' data:; connect-src 'self' " + props.issuer() + "; "
                + "form-action 'self'; frame-ancestors 'none'; base-uri 'self'";
    }

    static String[] paths(DocsProperties props, Environment environment) {
        var apiDocs = environment.getProperty("springdoc.api-docs.path", "/v3/api-docs");
        var swaggerUi = environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui.html");
        var swaggerDir = swaggerUi.substring(0, swaggerUi.lastIndexOf('/') + 1) + "swagger-ui";
        var scalar = environment.getProperty("scalar.path", "/scalar");
        var paths = new ArrayList<String>();
        paths.add(props.path("/docs"));
        paths.add(props.path("/docs/**"));
        paths.add(apiDocs);
        paths.add(apiDocs + "/**");
        paths.add(apiDocs + ".yaml");
        paths.add(apiDocs + ".yaml/**");
        paths.add(swaggerUi);
        paths.add(swaggerDir + "/**");
        paths.add(scalar);
        paths.add(scalar + "/**");
        return paths.stream().distinct().toArray(String[]::new);
    }
}
