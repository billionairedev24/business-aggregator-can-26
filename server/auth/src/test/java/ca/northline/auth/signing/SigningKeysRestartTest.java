package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.AuthServerApplication;
import ca.northline.auth.application.StepUpProofs;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.support.SharedPostgres;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.RestClient;

/**
 * Two (then three) complete northline-auth instances sharing one key directory: a token issued by one verifies on the
 * other and after a restart, both publish the same JWK set, and a rotation done by one is published by all.
 */
class SigningKeysRestartTest {

    @TempDir
    Path keys;

    private ConfigurableApplicationContext start(Path dir) {
        var pg = SharedPostgres.INSTANCE;
        return new SpringApplicationBuilder(AuthServerApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=" + pg.getUsername(),
                        "--spring.datasource.password=" + pg.getPassword(),
                        "--northline.auth.signing.local-dir=" + dir,
                        "--logging.level.root=warn");
    }

    private static String proof(ConfigurableApplicationContext app, String userId) {
        return app.getBean(StepUpProofs.class)
                .issue(userId, Factor.TOTP, Instant.now())
                .token();
    }

    private static String jwks(ConfigurableApplicationContext app) {
        var port = app.getEnvironment().getProperty("local.server.port");
        return RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/oauth2/jwks")
                .retrieve()
                .body(String.class);
    }

    private static List<String> kids(String jwks) {
        return JsonPath.read(jwks, "$.keys[*].kid");
    }

    @Test
    void tokensSurviveRestarts_andEveryInstancePublishesTheSameKeys() {
        var first = start(keys);
        String token;
        try (var second = start(keys)) {
            token = proof(first, "01J9ZD3V00000000000000RAV1");

            // Two instances at once: either one verifies the other's tokens.
            assertThat(second.getBean(JwtDecoder.class).decode(token).getSubject())
                    .isEqualTo("01J9ZD3V00000000000000RAV1");
            assertThat(kids(jwks(second))).isEqualTo(kids(jwks(first)));
            assertThat(JsonPath.<List<Object>>read(jwks(first), "$.keys[*].d")).isEmpty(); // public keys only
        } finally {
            first.close();
        }

        try (var restarted = start(keys)) {
            var decoder = restarted.getBean(JwtDecoder.class);
            var before = decoder.decode(token);
            assertThat(before.getSubject()).isEqualTo("01J9ZD3V00000000000000RAV1");
            // Same key after the restart: new tokens carry the same kid.
            assertThat(decoder.decode(proof(restarted, "01J9ZD3V00000000000000RAV1"))
                            .getHeaders())
                    .containsEntry("kid", before.getHeaders().get("kid"));
        }
    }

    @Test
    void rotationOnOneInstance_isPublishedByTheOthers_andOldTokensKeepVerifying() {
        try (var a = start(keys);
                var b = start(keys)) {
            var old = proof(a, "01J9ZD3V000000000000000JAS");
            var rotation = a.getBean(LocalFileSigningKeys.class).rotate(true);

            assertThat(kids(jwks(b))).contains(rotation.keyId()).hasSize(2);
            var fresh = proof(b, "01J9ZD3V000000000000000JAS");
            assertThat(a.getBean(JwtDecoder.class).decode(fresh).getHeaders()).containsEntry("kid", rotation.keyId());
            assertThat(b.getBean(JwtDecoder.class).decode(old).getSubject()).isEqualTo("01J9ZD3V000000000000000JAS");
        }
    }

    @Test
    void anotherKeyStore_doesNotTrustTheseTokens(@TempDir Path elsewhere) {
        String token;
        try (var a = start(keys)) {
            token = proof(a, "01J9ZD3V00000000000000PR1Y");
        }
        try (var stranger = start(elsewhere)) {
            assertThatThrownBy(() -> stranger.getBean(JwtDecoder.class).decode(token))
                    .isInstanceOf(JwtException.class);
        }
    }
}
