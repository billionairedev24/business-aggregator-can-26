package ca.northline.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * northline-auth: Spring Authorization Server (OAuth 2.1 / OIDC issuer) plus the JSON sign-in / registration API the
 * Studio renders its own UI for. See {@code docs/DECISIONS.md} (Auth workstream) for the hand-off to the BFF.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServerApplication {
    public static void main(String[] a) {
        SpringApplication.run(AuthServerApplication.class, a);
    }
}
