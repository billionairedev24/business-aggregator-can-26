package ca.northline.auth.clients;

import static ca.northline.auth.clients.Specs.bff;
import static ca.northline.auth.clients.Specs.mobile;
import static ca.northline.auth.clients.Specs.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.clients.OAuthClientSync.Action;
import ca.northline.auth.clients.OAuthClientSync.Outcome;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SharedPostgres;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciling configured clients into the database: create, update, secret rotation, orphans, dry run, the command. */
class OAuthClientSyncTest extends AuthIntegrationTest {

    @Autowired
    RegisteredClientRepository repository;

    @Autowired
    JdbcOperations jdbcOperations;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final String id = "sync-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);

    @Test
    void startUp_registeredTheConfiguredBffs() {
        var studio = repository.findByClientId("studio-bff");
        assertThat(studio).isNotNull();
        assertThat(studio.getRedirectUris()).containsExactly("http://localhost:3100/login/oauth2/code/studio");
        assertThat(studio.getScopes()).containsExactlyInAnyOrder("openid", "profile", "merchant");
        assertThat(studio.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(repository.findByClientId("consumer-bff")).isNotNull();
        assertThat(repository.findByClientId("console-bff")).isNotNull();
    }

    @Test
    void firstSyncCreates_secondChangesNothing() {
        var sync =
                sync(props(id, bff("{noop}" + id, "http://localhost/cb"), id + "-app", mobile("ca.northline.t:/cb")));

        assertThat(mine(sync.sync())).extracting(Outcome::action).containsExactly(Action.CREATE, Action.CREATE);
        assertThat(mine(sync.sync())).extracting(Outcome::action).containsExactly(Action.UNCHANGED, Action.UNCHANGED);
        var app = repository.findByClientId(id + "-app");
        assertThat(app).isNotNull();
        assertThat(app.getClientSecret()).isNull();
        assertThat(app.getTokenSettings().getRefreshTokenTimeToLive()).hasDays(30);
        assertThat(app.getClientSettings().<Boolean>getSetting(RegisteredClients.DPOP_REQUIRED))
                .isTrue();
    }

    @Test
    void changedConfiguration_updatesInPlace_namingTheFields() {
        sync(props(id, bff("{noop}" + id, "http://localhost/cb"))).sync();
        var before = repository.findByClientId(id);

        var outcomes = mine(sync(props(id, bff("{noop}" + id, "http://localhost/cb", "http://localhost/cb2")))
                .sync());

        assertThat(outcomes).containsExactly(new Outcome(id, Action.UPDATE, List.of("redirect-uris")));
        var after = repository.findByClientId(id);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getClientIdIssuedAt()).isEqualTo(before.getClientIdIssuedAt());
        assertThat(after.getRedirectUris()).containsExactlyInAnyOrder("http://localhost/cb", "http://localhost/cb2");
    }

    @Test
    void newSecretHash_rotatesTheSecret_atTheTokenEndpoint() throws Exception {
        var bcrypt = new BCryptPasswordEncoder(4);
        sync(props(id, bff("{bcrypt}" + bcrypt.encode("old-secret"), "http://localhost/cb")))
                .sync();
        assertClientAuthentication("old-secret", true);

        var outcomes = mine(sync(props(id, bff("{bcrypt}" + bcrypt.encode("new-secret"), "http://localhost/cb")))
                .sync());

        assertThat(outcomes).containsExactly(new Outcome(id, Action.UPDATE, List.of("secret")));
        assertClientAuthentication("old-secret", false);
        assertClientAuthentication("new-secret", true);
    }

    @Test
    void noopSecretReEncodedByTheServer_isNotAChange() throws Exception {
        var sync = sync(props(id, bff("{noop}plain-" + id, "http://localhost/cb")));
        sync.sync();
        assertClientAuthentication("plain-" + id, true); // Spring Authorization Server upgrades it to {bcrypt}
        assertThat(repository.findByClientId(id).getClientSecret()).startsWith("{bcrypt}");

        assertThat(mine(sync.sync())).containsExactly(new Outcome(id, Action.UNCHANGED, List.of()));
    }

    @Test
    void storedButNotConfigured_isReported_neverDeleted() {
        sync(props(id, bff("{noop}" + id, "http://localhost/cb"))).sync();

        var outcomes = sync(props(id + "-other", bff("{noop}" + id + "o", "http://localhost/cb")))
                .sync();

        assertThat(outcomes).contains(new Outcome(id, Action.NOT_IN_CONFIGURATION, List.of()));
        assertThat(repository.findByClientId(id)).isNotNull();
    }

    @Test
    void plan_writesNothing() {
        var outcomes =
                mine(sync(props(id, bff("{noop}" + id, "http://localhost/cb"))).plan());

        assertThat(outcomes).containsExactly(new Outcome(id, Action.CREATE, List.of()));
        assertThat(repository.findByClientId(id)).isNull();
    }

    @Test
    void command_listsAndSyncs_withoutTheServer() {
        var args = new String[] {
            "--spring.profiles.active=test",
            "--spring.datasource.url=" + SharedPostgres.INSTANCE.getJdbcUrl(),
            "--spring.datasource.username=" + SharedPostgres.INSTANCE.getUsername(),
            "--spring.datasource.password=" + SharedPostgres.INSTANCE.getPassword(),
            "--northline.oauth.clients." + id + ".type=public",
            "--northline.oauth.clients." + id + ".redirect-uris=ca.northline.cmd:/cb",
            "--northline.oauth.clients." + id + ".scopes=openid",
            "--northline.oauth.clients." + id + ".dpop-required=true"
        };

        assertThat(OAuthClientsCommand.run("list", args))
                .contains(new Outcome(id, Action.CREATE, List.of()))
                .contains(new Outcome("studio-bff", Action.UNCHANGED, List.of()));
        assertThat(repository.findByClientId(id)).isNull();

        assertThat(OAuthClientsCommand.run("sync", args)).contains(new Outcome(id, Action.CREATE, List.of()));
        assertThat(repository.findByClientId(id)).isNotNull();
        assertThat(OAuthClientsCommand.run("sync", args)).contains(new Outcome(id, Action.UNCHANGED, List.of()));
    }

    private OAuthClientSync sync(OAuthClientProperties props) {
        return new OAuthClientSync(
                new OAuthClientCatalog(props, ClientPolicy.LOCAL),
                repository,
                jdbcOperations,
                new TransactionTemplate(transactionManager));
    }

    /** This test's clients only (the shared database holds every other test's too). */
    private List<Outcome> mine(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> o.clientId().startsWith(id)).toList();
    }

    /** With good credentials a bogus code is {@code invalid_grant} (400); with bad ones {@code invalid_client} (401). */
    private void assertClientAuthentication(String secret, boolean accepted) throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic(id, secret))
                        .param("grant_type", "authorization_code")
                        .param("code", "bogus")
                        .param("redirect_uri", "http://localhost/cb")
                        .param("code_verifier", "v".repeat(43)))
                .andExpect(accepted ? status().isBadRequest() : status().isUnauthorized());
    }
}
