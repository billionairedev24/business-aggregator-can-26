package ca.northline.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.auth.support.AuthIntegrationTest;
import com.github.f4b6a3.ulid.UlidCreator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Engineering follow-ups (S-115 gap): {@code TOTP_KEY} could not be rotated — one key, no key id per row. Now the key
 * is versioned: a rotation adds a key with a new id, the old one stays in {@code TOTP_PREVIOUS_KEYS} for decryption,
 * and the re-encryption job moves every authenticator secret to the new key; nobody re-enrols. Exercised as the
 * runbook (key-rotation.md § 7) describes it.
 */
class TotpKeyRotationTest extends AuthIntegrationTest {

    /** The test profile's key (application-test.yml) — what the rows of every other test class are under. */
    static final String TEST_KEY = "bm9ydGhsaW5lLWxvY2FsLWRldi10b3RwLWtleS0zMmI=";
    /** Obviously fake. */
    static final String NEW_KEY = "dGVzdC1vbmx5LXJvdGF0ZWQtdG90cC1rZXktMzJieXQ=";

    @Autowired
    MeterRegistry meters;

    @Test
    void rotateTheKey_oldSecretsStillWork_theJobMovesThem_thenTheOldKeyCanGo() {
        var v1 = SecretCipher.of(TEST_KEY, new SecretCipher.Keys("v1", null));
        var users = List.of(user(), user(), user());
        users.forEach(u -> new JdbcSecondFactors(jdbc, v1).saveTotp(u, "SECRET" + u, Instant.now()));

        // the rotation: a new key with a new id; the old key kept for decryption
        var v2 = SecretCipher.of(NEW_KEY, new SecretCipher.Keys("v2", "v1=" + TEST_KEY));
        var afterSwitch = new JdbcSecondFactors(jdbc, v2);
        assertThat(afterSwitch.findTotp(users.getFirst()).orElseThrow().secret())
                .as("secrets under the previous key still verify codes")
                .isEqualTo("SECRET" + users.getFirst());
        afterSwitch.saveTotp(users.get(1), "ENROLLED-AGAIN", Instant.now()); // a new enrolment uses the new key
        assertThat(keyIds(users)).containsExactly("v1", "v2", "v1");

        var job = new TotpKeyRotation(jdbc, v2, meters);
        try {
            var outcome = job.run();
            assertThat(outcome.failed()).isZero();
            assertThat(outcome.reencrypted()).isGreaterThanOrEqualTo(2);
            while (job.run().reencrypted() > 0) {
                // other test classes' rows: batches of 200
            }
            assertThat(keyIds(users)).containsOnly("v2");
            assertThat(job.stale()).as("nothing left under the old key").isZero();
            assertThat(meters.counter(TotpKeyRotation.METRIC, "table", TotpKeyRotation.TABLE, "outcome", "reencrypted")
                            .count())
                    .isGreaterThanOrEqualTo(2);

            // the old key leaves TOTP_PREVIOUS_KEYS: the new key alone opens everything
            var onlyNew = new JdbcSecondFactors(jdbc, SecretCipher.of(NEW_KEY, new SecretCipher.Keys("v2", null)));
            assertThat(onlyNew.findTotp(users.getFirst()).orElseThrow().secret())
                    .isEqualTo("SECRET" + users.getFirst());
            assertThat(onlyNew.findTotp(users.get(1)).orElseThrow().secret()).isEqualTo("ENROLLED-AGAIN");
            assertThatThrownBy(() -> new JdbcSecondFactors(jdbc, v1).findTotp(users.getLast()))
                    .as("the old key no longer opens them")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            // the database is shared: put every row back under the test profile's key for the other test classes
            var back = SecretCipher.of(TEST_KEY, new SecretCipher.Keys("v1", "v2=" + NEW_KEY));
            var restore = new TotpKeyRotation(jdbc, back, meters);
            while (restore.run().reencrypted() > 0) {
                // batches of 200
            }
        }
    }

    @Test
    void aMisconfiguredRotationFailsAtStart_notOnTheFirstSignIn() {
        assertThatThrownBy(() -> SecretCipher.of(NEW_KEY, new SecretCipher.Keys("v1", "v1=" + TEST_KEY)))
                .hasMessageContaining("both the current key and a previous one");
        assertThatThrownBy(() -> SecretCipher.of(NEW_KEY, new SecretCipher.Keys("v2", TEST_KEY)))
                .hasMessageContaining("id=base64");
        assertThatThrownBy(() -> SecretCipher.of(NEW_KEY, new SecretCipher.Keys("v2", "v1=c2hvcnQ=")))
                .hasMessageContaining("32 bytes");
    }

    private String user() {
        return UlidCreator.getMonotonicUlid().toString();
    }

    private List<String> keyIds(List<String> users) {
        return users.stream()
                .map(u -> jdbc.sql("SELECT coalesce(key_id, 'none') FROM auth.totp_secrets WHERE user_id = :u")
                        .param("u", u)
                        .query(String.class)
                        .single())
                .toList();
    }
}
