package ca.northline.auth.clients;

import static ca.northline.auth.clients.Specs.bff;
import static ca.northline.auth.clients.Specs.mobile;
import static ca.northline.auth.clients.Specs.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.clients.OAuthClientCatalog.InvalidClientConfiguration;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Validation of {@code northline.oauth.clients}: fails fast, with every problem, before anything is written. */
class OAuthClientCatalogTest {

    private static final String BCRYPT = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuJ0HqcGXKJy4c3n1ok3uFxs5K0GqF7Qm";

    @Test
    void policyFollowsTheProfiles() {
        assertThat(ClientPolicy.of(env("prod", "cloud"))).isEqualTo(ClientPolicy.STRICT);
        assertThat(ClientPolicy.of(env("staging", "cloud"))).isEqualTo(ClientPolicy.STRICT);
        assertThat(ClientPolicy.of(env("dev", "cloud"))).isEqualTo(ClientPolicy.DEV);
        assertThat(ClientPolicy.of(env("local"))).isEqualTo(ClientPolicy.LOCAL);
        assertThat(ClientPolicy.of(env("test"))).isEqualTo(ClientPolicy.LOCAL);
    }

    @Test
    void strict_acceptsHttpsAndHashedSecrets() {
        var catalog = new OAuthClientCatalog(
                props(
                        "studio-bff", bff(BCRYPT, "https://studio.northline.ca/login/oauth2/code/studio"),
                        "mobile-consumer", mobile("ca.northline.app:/oauth2redirect")),
                ClientPolicy.STRICT);

        assertThat(catalog.clients())
                .extracting(OAuthClientCatalog.Declared::clientId)
                .containsExactlyInAnyOrder("studio-bff", "mobile-consumer");
    }

    @Test
    void strict_refusesHttpRedirects_evenOnLoopback() {
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("studio-bff", bff(BCRYPT, "http://localhost:3100/login/oauth2/code/studio")),
                        ClientPolicy.STRICT))
                .isInstanceOf(InvalidClientConfiguration.class)
                .hasMessageContaining("studio-bff: redirect URI http://localhost:3100/login/oauth2/code/studio must use"
                        + " https outside local");
    }

    @Test
    void dev_allowsHttpOnlyOnLoopback() {
        new OAuthClientCatalog(props("a", bff("{noop}a", "http://localhost:3100/cb")), ClientPolicy.DEV);

        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff("{noop}a", "http://studio.dev.northline.ca/cb")), ClientPolicy.DEV))
                .hasMessageContaining("must use https outside local");
    }

    @Test
    void local_allowsHttpAnywhere() {
        new OAuthClientCatalog(props("a", bff("{noop}a", "http://192.168.1.20:3100/cb")), ClientPolicy.LOCAL);
    }

    @Test
    void strict_refusesUnhashedSecrets_everyPolicyRefusesPlainOnes() {
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff("{noop}dev", "https://x.northline.ca/cb")), ClientPolicy.STRICT))
                .hasMessageContaining("{noop}… (unhashed) is not allowed under staging/prod");
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff("plain-secret", "https://x.northline.ca/cb")), ClientPolicy.LOCAL))
                .hasMessageContaining("must be an encoded value such as {bcrypt}");
    }

    @Test
    void missingRequiredSettings_areAllListed() {
        var noType = new ClientSpec(
                null,
                false,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                false);

        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props(
                                "studio-bff", bff(null, "https://x.northline.ca/cb"),
                                "console-bff", bff(BCRYPT),
                                "other", noType),
                        ClientPolicy.STRICT))
                .isInstanceOf(InvalidClientConfiguration.class)
                .hasMessageContaining("studio-bff: secret-hash is required for a confidential client")
                .hasMessageContaining("STUDIO_BFF_SECRET_HASH")
                .hasMessageContaining("console-bff: redirect-uris is required for authorization_code")
                .hasMessageContaining("other: type is required");
    }

    @Test
    void optionalClientWithoutSecret_isSkipped_butARequiredOneFails() {
        var consumer = new ClientSpec(
                ClientSpec.Type.CONFIDENTIAL,
                true,
                null,
                "",
                List.of("https://northline.ca/cb"),
                List.of(),
                List.of("openid"),
                List.of("authorization_code"),
                true,
                false,
                Duration.ofMinutes(10),
                Duration.ofHours(12),
                false);

        var catalog = new OAuthClientCatalog(
                props("consumer-bff", consumer, "studio-bff", bff(BCRYPT, "https://b.northline.ca/cb")),
                ClientPolicy.STRICT);

        assertThat(catalog.declares("consumer-bff")).isFalse();
        assertThat(catalog.declares("studio-bff")).isTrue();
    }

    @Test
    void strict_withNoClientAtAll_fails() {
        assertThatThrownBy(() -> new OAuthClientCatalog(props(), ClientPolicy.STRICT))
                .hasMessageContaining("no client is configured");
    }

    @Test
    void sharedSecrets_publicClientsWithSecrets_andForeignSchemesForBffs_areRefused() {
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props(
                                "a",
                                bff("{noop}same", "http://localhost/a"),
                                "b",
                                bff("{noop}same", "http://localhost/b")),
                        ClientPolicy.LOCAL))
                .hasMessageContaining("share one secret-hash");
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff(BCRYPT, "ca.northline.app:/oauth2redirect")), ClientPolicy.LOCAL))
                .hasMessageContaining("must use https (a public client may use");
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff(BCRYPT, "https://x.northline.ca/cb#frag")), ClientPolicy.LOCAL))
                .hasMessageContaining("must not have a fragment");
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        props("a", bff(BCRYPT, "https://*.northline.ca/cb")), ClientPolicy.LOCAL))
                .hasMessageContaining("without wildcards");
    }

    @Test
    void aPublicClientThatRefreshes_mustRequireDpop() {
        var withoutDpop = mobile("ca.northline.app:/oauth2redirect");
        var spec = new ClientSpec(
                withoutDpop.type(),
                false,
                null,
                null,
                withoutDpop.redirectUris(),
                List.of(),
                withoutDpop.scopes(),
                withoutDpop.grantTypes(),
                true,
                false,
                withoutDpop.accessTokenTtl(),
                withoutDpop.refreshTokenTtl(),
                false);
        assertThatThrownBy(() -> new OAuthClientCatalog(props("app", spec), ClientPolicy.LOCAL))
                .hasMessageContaining("app: a public client that refreshes needs dpop-required: true");
        assertThat(new OAuthClientCatalog(props("app", withoutDpop), ClientPolicy.STRICT).declares("app"))
                .isTrue();
    }

    @Test
    void partners_areValidated_everyProblemListed() throws Exception {
        var ec = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        var pub = ec.toPublicJWK().toJSONString();
        var ok = Specs.partner(List.of(pub), List.of("api.read"), List.of("01J9ZD3V00000000000000PWM1"));
        var catalog = new OAuthClientCatalog(Specs.withPartners(props(), Map.of("acme", ok)), ClientPolicy.STRICT);
        assertThat(catalog.declares("partner:acme")).isTrue();

        var bad = new PartnerSpec(
                null,
                "http://keys.acme.example/jwks.json",
                List.of(ec.toJSONString(), pub),
                List.of("api.read", "merchant"),
                List.of("not-a-ulid"),
                Duration.ofHours(2),
                false);
        assertThatThrownBy(() ->
                        new OAuthClientCatalog(Specs.withPartners(props(), Map.of("Acme!", bad)), ClientPolicy.STRICT))
                .isInstanceOf(InvalidClientConfiguration.class)
                .hasMessageContaining("partner:Acme!: partner name must be")
                .hasMessageContaining("set exactly one of jwk-set-url and public-keys")
                .hasMessageContaining("jwk-set-url http://keys.acme.example/jwks.json must use https")
                .hasMessageContaining("public-keys[0] contains a private key")
                .hasMessageContaining("scope merchant is not a partner scope")
                .hasMessageContaining("merchant not-a-ulid is not a business id")
                .hasMessageContaining("access-token-ttl must be between 1 s and 1 h");
        assertThatThrownBy(() -> new OAuthClientCatalog(
                        Specs.withPartners(
                                props(),
                                Map.of("acme", Specs.partner(List.of(pub, pub), List.of("api.read"), List.of()))),
                        ClientPolicy.LOCAL))
                .hasMessageContaining("public-keys[1] needs its own kid")
                .hasMessageContaining("merchants is empty");
    }

    private static MockEnvironment env(String... profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
