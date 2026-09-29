package ca.northline.auth.clients;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Client registration beans, shared by the auth server and {@link OAuthClientsCommand}: the validated catalog (an
 * invalid configuration stops start-up), the reconciler, and — unless {@code northline.oauth.sync-on-startup=false} —
 * a sync on every start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthClientProperties.class)
public class OAuthClientsConfig {

    @Bean
    OAuthClientCatalog oauthClientCatalog(OAuthClientProperties props, Environment environment) {
        return new OAuthClientCatalog(props, ClientPolicy.of(environment));
    }

    @Bean
    OAuthClientSync oauthClientSync(
            OAuthClientCatalog catalog,
            RegisteredClientRepository repository,
            JdbcOperations jdbc,
            PlatformTransactionManager transactions) {
        return new OAuthClientSync(catalog, repository, jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "northline.oauth.sync-on-startup", matchIfMissing = true)
    ApplicationRunner oauthClientsOnStartup(OAuthClientSync sync) {
        return _ -> sync.sync();
    }
}
