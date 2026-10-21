package ca.northline.auth.clients;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
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
    ApplicationRunner oauthClientsOnStartup(OAuthClientSync sync, Environment environment) {
        return _ -> {
            sync.sync();
            sync.syncConsolePartners(ClientPolicy.of(environment));
        };
    }

    /**
     * S-158: the console's partners ({@code developer.partner_clients}) are registered every
     * {@code northline.oauth.console-partners.refresh} (30 s) — a new partner, a rotation, a disable or an owner's
     * consent reaches token issuance within that time. Off with {@code northline.oauth.console-partners.enabled=false};
     * not under {@code test} (tests call the sync).
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Profile("!test")
    @ConditionalOnBooleanProperty(name = "northline.oauth.console-partners.enabled", matchIfMissing = true)
    static class ConsolePartnersRefresh {

        private final OAuthClientSync sync;
        private final ClientPolicy policy;

        ConsolePartnersRefresh(OAuthClientSync sync, Environment environment) {
            this.sync = sync;
            this.policy = ClientPolicy.of(environment);
        }

        @Scheduled(fixedDelayString = "${northline.oauth.console-partners.refresh:PT30S}", initialDelayString = "PT10S")
        void refresh() {
            try {
                sync.syncConsolePartners(policy);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ConsolePartnersRefresh.class)
                        .warn("Console partner sync failed; retrying at the next run: {}", e.toString());
            }
        }
    }
}
