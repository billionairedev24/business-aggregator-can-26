package ca.northline.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.clients.*}: the confidential BFF clients (ARCHITECTURE.md § Identity). Secrets come from the
 * environment outside {@code local}.
 */
@ConfigurationProperties("northline.clients")
record ClientProperties(Bff studio, Bff consumer, Bff console) {

    /** One BFF: client secret (already encoded, e.g. {@code {noop}…} or {@code {bcrypt}…}) and its redirect URIs. */
    record Bff(String secret, List<String> redirectUris, List<String> postLogoutRedirectUris) {}
}
