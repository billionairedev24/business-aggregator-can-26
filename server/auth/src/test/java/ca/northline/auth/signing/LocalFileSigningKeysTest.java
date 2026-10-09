package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.signing.SigningKeys.KeyState.Status;
import ca.northline.auth.support.AuthIntegrationTest.MutableClock;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** The {@code local} key store: persistence across restarts, rotation with an overlap window, retirement. */
class LocalFileSigningKeysTest {

    private static final Duration AHEAD = Duration.ofMinutes(10);
    private static final Duration RETIRE = Duration.ofHours(1);

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void now() {
        clock.set(Instant.now());
    }

    private LocalFileSigningKeys store() {
        return new LocalFileSigningKeys(dir, clock, AHEAD, RETIRE);
    }

    @Test
    void firstUse_createsAPrivateFileWithOneActiveKey() throws Exception {
        var keys = store();

        assertThat(keys.file()).exists();
        assertThat(Files.getPosixFilePermissions(keys.file())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(keys.published()).hasSize(1).allMatch(k -> !k.isPrivate());
        assertThat(keys.active().keyId()).isEqualTo(keys.published().getFirst().getKeyID());
        assertThat(keys.describe()).extracting(SigningKeys.KeyState::status).containsExactly(Status.ACTIVE);
    }

    @Test
    void restart_keepsTheKey_soTokensStayValid() {
        var token = sign(store(), "before-restart");

        var restarted = store();

        assertThat(decode(restarted, token.getTokenValue()).getSubject()).isEqualTo("before-restart");
        assertThat(restarted.active().keyId()).isEqualTo(token.getHeaders().get("kid"));
    }

    @Test
    void kidIsTheThumbprint() throws Exception {
        var key = store().published().getFirst();
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
    }

    @Test
    void rotation_publishesAhead_switches_thenRetiresTheOldKey() {
        var keys = store();
        var old = keys.active().keyId();
        var oldToken = sign(keys, "old");

        var rotation = keys.rotate(false);

        // Published, not signing yet: JWK set caches learn the new key first.
        assertThat(rotation.activatesAt()).isEqualTo(clock.instant().plus(AHEAD));
        assertThat(keys.active().keyId()).isEqualTo(old);
        assertThat(keys.published()).extracting(JWK::getKeyID).containsExactly(rotation.keyId(), old);
        assertThat(keys.describe())
                .extracting(SigningKeys.KeyState::status)
                .containsExactly(Status.ACTIVE, Status.NEXT);

        clock.advanceSeconds(AHEAD.toSeconds());
        var newToken = sign(keys, "new");
        assertThat(newToken.getHeaders()).containsEntry("kid", rotation.keyId());
        // Overlap window: the old key still verifies what it signed.
        assertThat(decode(keys, oldToken.getTokenValue()).getSubject()).isEqualTo("old");
        assertThat(decode(keys, newToken.getTokenValue()).getSubject()).isEqualTo("new");
        assertThat(keys.describe())
                .extracting(SigningKeys.KeyState::status)
                .containsExactly(Status.RETIRING, Status.ACTIVE);

        clock.advanceSeconds(RETIRE.toSeconds());
        assertThat(keys.published()).extracting(JWK::getKeyID).containsExactly(rotation.keyId());
        assertThatThrownBy(() -> decode(keys, oldToken.getTokenValue())).isInstanceOf(JwtException.class);
        assertThat(decode(keys, newToken.getTokenValue()).getSubject()).isEqualTo("new");
    }

    @Test
    void rotationByAnotherInstance_isPickedUpFromTheFile() {
        var a = store();
        var b = store();
        var rotation = b.rotate(true);

        clock.advanceSeconds(31); // re-read at the latest every 30 s even when the file time didn't move

        assertThat(a.active().keyId()).isEqualTo(rotation.keyId());
        assertThat(a.published()).hasSize(2);
    }

    @Test
    void immediateRotation_thenRetire_dropsACompromisedKey() {
        var keys = store();
        var compromised = keys.active().keyId();
        var token = sign(keys, "leaked");

        keys.rotate(true);
        assertThat(keys.active().keyId()).isNotEqualTo(compromised);
        keys.retire(compromised);

        assertThat(keys.published()).extracting(JWK::getKeyID).doesNotContain(compromised);
        assertThatThrownBy(() -> decode(keys, token.getTokenValue())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> keys.retire(keys.active().keyId())).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Engineering follow-ups (S-115 drill finding): a planned rotation still pending survived {@code rotate
     * --immediately} and would have taken over from the emergency key at its time.
     */
    @Test
    void immediateRotation_alsoRetiresAPendingScheduledKey() {
        var keys = store();
        var compromised = keys.active().keyId();
        var planned = keys.rotate(false); // NEXT, created before the compromise

        var emergency = keys.rotate(true);

        assertThat(keys.active().keyId()).isEqualTo(emergency.keyId());
        assertThat(keys.published()).extracting(JWK::getKeyID).doesNotContain(planned.keyId());
        assertThat(keys.describe()).extracting(SigningKeys.KeyState::status).doesNotContain(Status.NEXT);
        keys.retire(compromised);
        clock.advanceSeconds(AHEAD.toSeconds() + 1); // the planned key's time comes: the emergency key keeps signing
        assertThat(keys.active().keyId()).isEqualTo(emergency.keyId());
        assertThat(keys.published()).extracting(JWK::getKeyID).containsExactly(emergency.keyId());
    }

    @Test
    void rotationJob_rotatesOnlyWhenTheNewestKeyIsOldEnough() {
        var keys = store();
        assertThat(keys.rotateIfOlderThan(Duration.ofDays(90))).isEmpty();

        clock.advanceSeconds(Duration.ofDays(90).toSeconds());

        assertThat(keys.rotateIfOlderThan(Duration.ofDays(90))).isPresent();
        assertThat(keys.rotateIfOlderThan(Duration.ofDays(90))).isEmpty(); // the pending key counts as newest
    }

    @Test
    void theFileIsAJwkSetWithPrivateKeys() throws Exception {
        var keys = store();
        keys.rotate(false);
        var set = JWKSet.load(keys.file().toFile());
        assertThat(set.getKeys()).hasSize(2).allMatch(JWK::isPrivate);
        assertThat(set.getKeys().getFirst().getExpirationTime()).isNotNull();
        assertThat(set.getKeys().getLast().getNotBeforeTime()).isNotNull();
    }

    private Jwt sign(SigningKeys keys, String subject) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer("http://localhost:9000")
                .subject(subject)
                .audience(List.of("northline-api"))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofDays(1))) // long enough for the clock moves in these tests
                .claim("acr", "mfa")
                .build();
        return new KeyStoreJwtEncoder(keys)
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.ES256).build(), claims));
    }

    static Jwt decode(SigningKeys keys, String token) {
        var decoder = NimbusJwtDecoder.withJwkSource(
                        (selector, _) -> selector.select(new JWKSet(List.<JWK>copyOf(keys.published()))))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        return decoder.decode(token);
    }
}
