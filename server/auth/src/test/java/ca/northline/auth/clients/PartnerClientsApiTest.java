package ca.northline.auth.clients;

import static ca.northline.auth.clients.Specs.props;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SharedPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-30: partners get tokens with {@code client_credentials} and a {@code private_key_jwt} assertion (RFC 7523), with
 * registered public keys or a JWK Set URL (a WireMock stand-in), key rotation, scopes, the merchant binding, replay,
 * lifetime and audience checks, revocation, the rate limit and the audit row per token. Each test registers its own
 * partners through the catalogue and the sync, as the {@code oauthClients} Job does.
 */
class PartnerClientsApiTest extends AuthIntegrationTest {

    private static final String ISSUER = "http://localhost:9000";
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String PWM = "01J9ZD3V00000000000000PWM1";
    private static final String PWP = "01J9ZD3V00000000000000PWP1";
    private static final WireMockServer JWKS =
            new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    RegisteredClientRepository repository;

    @Autowired
    JdbcOperations jdbcOperations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeAll
    static void start() {
        JWKS.start();
    }

    @AfterAll
    static void stop() {
        JWKS.stop();
    }

    /** A partner's key pair (its private part stays with the partner; we register or publish the public part). */
    private record Key(JWK jwk, JWSSigner signer, JWSAlgorithm alg) {
        static Key ec() {
            try {
                var key = new ECKeyGenerator(Curve.P_256)
                        .keyID(UUID.randomUUID().toString())
                        .generate();
                return new Key(key, new ECDSASigner(key), JWSAlgorithm.ES256);
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        static Key rsa() {
            try {
                var key = new RSAKeyGenerator(2048)
                        .keyID(UUID.randomUUID().toString())
                        .generate();
                return new Key(key, new RSASSASigner(key), JWSAlgorithm.RS256);
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        String publicJson() {
            return jwk.toPublicJWK().toJSONString();
        }
    }

    private static String name() {
        return "p" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    private void register(String name, PartnerSpec partner) {
        new OAuthClientSync(
                        new OAuthClientCatalog(Specs.withPartners(props(), Map.of(name, partner)), ClientPolicy.LOCAL),
                        repository,
                        jdbcOperations,
                        new TransactionTemplate(transactionManager))
                .sync();
    }

    private String registered(List<String> scopes, List<String> merchants, Key... keys) {
        var name = name();
        register(name, Specs.partner(List.of(keys).stream().map(Key::publicJson).toList(), scopes, merchants));
        return PartnerSpec.clientId(name);
    }

    private String published(String path, List<String> scopes, Key... keys) {
        publish(path, keys);
        var name = name();
        register(
                name,
                new PartnerSpec(
                        "Acme Books",
                        JWKS.baseUrl() + path,
                        List.of(),
                        scopes,
                        List.of(PWM),
                        Duration.ofMinutes(15),
                        false));
        return PartnerSpec.clientId(name);
    }

    private static void publish(String path, Key... keys) {
        var set = new JWKSet(
                List.of(keys).stream().map(k -> k.jwk().toPublicJWK()).toList());
        JWKS.stubFor(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(set.toString(true))));
    }

    private String assertion(String clientId, Key key, Consumer<JWTClaimsSet.Builder> changes) {
        var now = clock.instant();
        var claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .subject(clientId)
                .audience(ISSUER)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(120)));
        changes.accept(claims);
        try {
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(key.alg()).keyID(key.jwk().getKeyID()).build(), claims.build());
            jwt.sign(key.signer());
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private String assertion(String clientId, Key key) {
        return assertion(clientId, key, _ -> {});
    }

    private ResultActions token(String assertion, @Nullable String scope) throws Exception {
        // Spring Authorization Server wants the client_id too (RFC 7523 makes it optional).
        var request = post("/oauth2/token")
                .accept(MediaType.APPLICATION_JSON)
                .param("client_id", String.valueOf(claims(assertion).get("iss")))
                .param("grant_type", "client_credentials")
                .param("client_assertion_type", ASSERTION_TYPE)
                .param("client_assertion", assertion);
        return mvc.perform(scope == null ? request : request.param("scope", scope));
    }

    private static Map<String, Object> claims(String jwt) {
        return JsonPath.read(
                new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8), "$");
    }

    private long audited(String clientId) {
        return jdbc.sql(
                        "SELECT count(*) FROM developer.audit_log WHERE actor_id = :c AND action = 'auth.partner_token_issued'")
                .param("c", clientId)
                .query(Long.class)
                .single();
    }

    @Nested
    class Tokens {

        @Test
        @SuppressWarnings("unchecked")
        void aRegisteredKey_getsAScopedToken_boundToItsBusinesses_andAudited() throws Exception {
            var key = Key.ec();
            var partner = registered(List.of("api.read"), List.of(PWM, PWP), key);

            var body = token(assertion(partner, key), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token_type").value("Bearer"))
                    .andExpect(jsonPath("$.refresh_token").doesNotExist())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            var access = claims(JsonPath.read(body, "$.access_token"));
            assertThat(access.get("sub")).isEqualTo(partner);
            assertThat((List<Object>) access.get("aud")).containsExactlyInAnyOrder(partner, "northline-api");
            assertThat(access.get("scope")).isEqualTo("api.read");
            assertThat(access.get("roles")).isEqualTo(List.of("partner"));
            assertThat(access.get("merchants")).isEqualTo(List.of(PWM, PWP));
            assertThat(access).doesNotContainKeys("acr", "amr");
            assertThat(((Number) access.get("exp")).longValue() - ((Number) access.get("iat")).longValue())
                    .isEqualTo(900);
            assertThat(audited(partner)).isEqualTo(1);
            var detail = jdbc.sql("SELECT after::text FROM developer.audit_log WHERE actor_id = :c")
                    .param("c", partner)
                    .query(String.class)
                    .single();
            assertThat(detail).contains("api.read", PWM, PWP);
        }

        @Test
        void aJwkSetUrl_withAnRsaKey_works() throws Exception {
            var key = Key.rsa();
            var partner = published("/jwks-" + name(), List.of("api.read", "api.write"), key);
            var body = token(assertion(partner, key), "api.write")
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(claims(JsonPath.read(body, "$.access_token")).get("scope"))
                    .isEqualTo("api.write");
        }

        @Test
        void scopes_areLimitedToThePartners() throws Exception {
            var key = Key.ec();
            var partner = registered(List.of("api.read"), List.of(PWM), key);
            token(assertion(partner, key), "api.write")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_scope"));
            token(assertion(partner, key), "openid merchant")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_scope"));
            assertThat(audited(partner)).isZero();
        }
    }

    @Nested
    class Rotation {

        @Test
        void twoRegisteredKeys_areValidAtOnce() throws Exception {
            var old = Key.ec();
            var next = Key.rsa();
            var partner = registered(List.of("api.read"), List.of(PWM), old, next);
            token(assertion(partner, old), null).andExpect(status().isOk());
            token(assertion(partner, next), null).andExpect(status().isOk());
        }

        @Test
        void aKeyAddedToTheJwkSet_isPickedUp_withoutARestart() throws Exception {
            var path = "/jwks-" + name();
            var old = Key.ec();
            var partner = published(path, List.of("api.read"), old);
            token(assertion(partner, old), null).andExpect(status().isOk());

            var next = Key.ec();
            publish(path, old, next); // the partner publishes its next key, then signs with it
            token(assertion(partner, next), null).andExpect(status().isOk());
        }

        @Test
        void aRemovedRegisteredKey_stopsWorking_afterTheSync() throws Exception {
            var old = Key.ec();
            var next = Key.ec();
            var name = name();
            register(
                    name,
                    Specs.partner(List.of(old.publicJson(), next.publicJson()), List.of("api.read"), List.of(PWM)));
            var partner = PartnerSpec.clientId(name);
            token(assertion(partner, old), null).andExpect(status().isOk());

            register(name, Specs.partner(List.of(next.publicJson()), List.of("api.read"), List.of(PWM)));

            token(assertion(partner, old), null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_client"));
            token(assertion(partner, next), null).andExpect(status().isOk());
        }
    }

    @Nested
    class Assertions {

        @Test
        void aReplayedAssertion_isRefused() throws Exception {
            var key = Key.ec();
            var partner = registered(List.of("api.read"), List.of(PWM), key);
            var once = assertion(partner, key);
            token(once, null).andExpect(status().isOk());
            token(once, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_client"));
        }

        @Test
        void audience_expiry_issuedAt_andJti_areEnforced() throws Exception {
            var key = Key.ec();
            var partner = registered(List.of("api.read"), List.of(PWM), key);
            var now = clock.instant();
            List<Consumer<JWTClaimsSet.Builder>> bad = List.of(
                    c -> c.audience("https://someone-else.example"),
                    c -> c.expirationTime(Date.from(now.minusSeconds(120))),
                    c -> c.expirationTime(Date.from(now.plus(Duration.ofMinutes(30)))),
                    c -> c.issueTime(Date.from(now.plusSeconds(300))),
                    c -> c.issueTime(null),
                    c -> c.jwtID(null),
                    c -> c.issuer("partner:someone-else"),
                    c -> c.subject("partner:someone-else"));
            for (var change : bad) {
                token(assertion(partner, key, change), null)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error").value("invalid_client"));
            }
            token(assertion(partner, key, c -> c.audience(ISSUER + "/oauth2/token")), null)
                    .andExpect(status().isOk());
        }

        @Test
        void aKeyItDidntRegister_isRefused() throws Exception {
            var partner = registered(List.of("api.read"), List.of(PWM), Key.ec());
            token(assertion(partner, Key.ec()), null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_client"));
            assertThat(audited(partner)).isZero();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void aRevokedPartner_getsNoToken() throws Exception {
            var key = Key.ec();
            var name = name();
            var spec = Specs.partner(List.of(key.publicJson()), List.of("api.read"), List.of(PWM));
            register(name, spec);
            var partner = PartnerSpec.clientId(name);
            token(assertion(partner, key), null).andExpect(status().isOk());

            register(
                    name,
                    new PartnerSpec(
                            spec.name(),
                            null,
                            spec.publicKeys(),
                            spec.scopes(),
                            spec.merchants(),
                            spec.accessTokenTtl(),
                            true));

            token(assertion(partner, key), null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_client"));
            assertThat(audited(partner)).isEqualTo(1);
        }

        @Test
        void tokens_areRateLimitedPerPartner() throws Exception {
            var key = Key.ec();
            var partner = registered(List.of("api.read"), List.of(PWM), key);
            var other = registered(List.of("api.read"), List.of(PWM), key);
            for (var i = 0; i < 3; i++) {
                token(assertion(partner, key), null).andExpect(status().isOk());
            }
            token(assertion(partner, key), null)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.error").value("rate_limited"));
            assertThat(audited(partner)).isEqualTo(3);
            token(assertion(other, key), null).andExpect(status().isOk()); // per partner
        }

        @Test
        void theCommand_registersAPartner_fromConfiguration() {
            var key = Key.ec();
            var name = name();
            var outcomes = OAuthClientsCommand.run(
                    "sync",
                    "--spring.profiles.active=test",
                    "--spring.datasource.url=" + SharedPostgres.INSTANCE.getJdbcUrl(),
                    "--spring.datasource.username=" + SharedPostgres.INSTANCE.getUsername(),
                    "--spring.datasource.password=" + SharedPostgres.INSTANCE.getPassword(),
                    "--northline.oauth.partners." + name + ".public-keys[0]=" + key.publicJson(),
                    "--northline.oauth.partners." + name + ".scopes=api.read",
                    "--northline.oauth.partners." + name + ".merchants=" + PWM);
            assertThat(outcomes)
                    .contains(new OAuthClientSync.Outcome(
                            PartnerSpec.clientId(name), OAuthClientSync.Action.CREATE, List.of()));
            var client = repository.findByClientId(PartnerSpec.clientId(name));
            assertThat(client).isNotNull();
            assertThat(RegisteredClients.isPartner(client)).isTrue();
            assertThat(RegisteredClients.partnerMerchants(client)).containsExactly(PWM);
        }
    }
}
