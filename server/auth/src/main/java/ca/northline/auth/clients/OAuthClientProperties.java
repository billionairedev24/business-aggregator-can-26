package ca.northline.auth.clients;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.oauth.*}: the OAuth clients this environment registers (docs/runbooks/README.md § OAuth clients).
 *
 * @param syncOnStartup reconcile {@link #clients} at every start ({@code OAUTH_CLIENTS_SYNC_ON_STARTUP}, default true);
 *     turn it off to register only through the admin command / Kubernetes Job
 * @param clients by OAuth {@code client_id}
 */
@ConfigurationProperties("northline.oauth")
record OAuthClientProperties(
        @DefaultValue("true") boolean syncOnStartup,
        @DefaultValue Map<String, ClientSpec> clients) {}
