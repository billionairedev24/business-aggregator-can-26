package ca.northline.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.developer.application.DeveloperStore;
import ca.northline.developer.application.WebhookKeyRotation;
import ca.northline.developer.application.WebhookSecretCipher;
import ca.northline.platform.WebhookSecretBox;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Engineering follow-ups (S-115 gap): {@code WEBHOOK_SECRET_KEY} could not be rotated. Now the key is versioned
 * ({@code WEBHOOK_SECRET_KEY_ID}, {@code secret_ref = db:aes-gcm:<id>}): the old key stays in {@code
 * WEBHOOK_SECRET_PREVIOUS_KEYS} for decryption (api and worker) while the job re-encrypts every endpoint's secret — and
 * a previous secret still signing next to it — with the new key. Exercised as key-rotation.md § 3 describes it.
 */
class WebhookKeyRotationTest extends IntegrationTest {

    /** The development key every other test class encrypts with (WebhookSecretBox.DEV_KEY). */
    static final String DEV_KEY = "bm9ydGhsaW5lLWRldi13ZWJob29rLWtleS0zMmJ5dGU=";
    /** Obviously fake. */
    static final String NEW_KEY = "dGVzdC1vbmx5LXJvdGF0ZWQtd2ViaG9vay1rZXktMzI=";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeveloperStore store;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Clock clock;

    /** The api's cipher as it is configured by the variables: a box over the key ring. */
    record BoxCipher(WebhookSecretBox box) implements WebhookSecretCipher {
        static BoxCipher of(String key, String id, @Nullable String previous) {
            return new BoxCipher(WebhookSecretBox.of(key, id, previous, false).orElseThrow());
        }

        @Override
        public String keyRef() {
            return box.keyRef();
        }

        @Override
        public byte[] encrypt(String secret) {
            return box.encrypt(secret);
        }

        @Override
        public String decrypt(byte[] encrypted) {
            return box.decrypt(encrypted);
        }

        @Override
        public Opened open(byte[] encrypted, @Nullable String keyRef) {
            var opened = box.open(encrypted, keyRef);
            return new Opened(opened.secret(), opened.keyRef());
        }
    }

    @Test
    void rotateTheKey_secretsStillDecrypt_theJobMovesThem_partnersNoticeNothing() {
        var v1 = BoxCipher.of(DEV_KEY, "v1", null);
        var plain = endpoint(v1, "whsec_plain", null);
        var rotating = endpoint(v1, "whsec_new", "whsec_old"); // a business mid-way through its own secret rotation

        var v2 = BoxCipher.of(NEW_KEY, "v2", "v1=" + DEV_KEY);
        assertThat(v2.decrypt(secret(plain)))
                .as("the worker still signs with old rows")
                .isEqualTo("whsec_plain");

        var job = new WebhookKeyRotation(store, v2, meters, clock);
        try {
            assertThat(job.run().reencrypted()).isGreaterThanOrEqualTo(2);
            while (job.run().reencrypted() > 0) {
                // other test classes' endpoints: batches of 200 (their placeholder bytes fail and are counted)
            }
            assertThat(refs(List.of(plain, rotating))).containsOnly("db:aes-gcm:v2");

            // the old key leaves WEBHOOK_SECRET_PREVIOUS_KEYS: the new key alone opens both secrets; they are unchanged
            var onlyNew = BoxCipher.of(NEW_KEY, "v2", null);
            assertThat(onlyNew.decrypt(secret(plain))).isEqualTo("whsec_plain");
            assertThat(onlyNew.decrypt(secret(rotating))).isEqualTo("whsec_new");
            assertThat(onlyNew.decrypt(previous(rotating))).isEqualTo("whsec_old");
            assertThatThrownBy(() -> v1.decrypt(secret(plain))).isInstanceOf(IllegalStateException.class);
        } finally {
            // the database is shared: every endpoint back under the development key for the other test classes
            var restore = new WebhookKeyRotation(store, BoxCipher.of(DEV_KEY, "v1", "v2=" + NEW_KEY), meters, clock);
            while (restore.run().reencrypted() > 0) {
                // batches of 200
            }
        }
    }

    private String endpoint(WebhookSecretCipher cipher, String secret, @Nullable String previous) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into developer.webhook_endpoints (id, merchant_id, url, secret_ref, secret_enc, secret_prev_enc,
                               secret_prev_until, events, active, created_at)
                        values (?, ?, 'https://partner.example/hooks', ?, ?, ?,
                                case when ? then now() + interval '1 day' end, array['order.placed'], true, now())""")
                .params(
                        id,
                        Ids.next(),
                        cipher.keyRef(),
                        cipher.encrypt(secret),
                        previous == null ? null : cipher.encrypt(previous),
                        previous != null)
                .update();
        return id;
    }

    private byte[] secret(String endpoint) {
        return jdbc.sql("select secret_enc from developer.webhook_endpoints where id = ?")
                .params(endpoint)
                .query(byte[].class)
                .single();
    }

    private byte[] previous(String endpoint) {
        return jdbc.sql("select secret_prev_enc from developer.webhook_endpoints where id = ?")
                .params(endpoint)
                .query(byte[].class)
                .single();
    }

    private List<String> refs(List<String> endpoints) {
        return endpoints.stream()
                .map(e -> jdbc.sql("select secret_ref from developer.webhook_endpoints where id = ?")
                        .params(e)
                        .query(String.class)
                        .single())
                .toList();
    }
}
