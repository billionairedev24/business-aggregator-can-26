package ca.northline.auth.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Engineering follow-ups (S-115 gap; docs/runbooks/key-rotation.md § 7): after {@code TOTP_KEY} is rotated (a new key
 * with a new {@code TOTP_KEY_ID}, the old one in {@code TOTP_PREVIOUS_KEYS}), re-encrypts every authenticator secret
 * with the current key — the same pattern as the KMS re-wrap job ({@code KMS_REWRAP_EVERY}). Nobody re-enrols: the
 * secrets themselves never change. Runs every {@code TOTP_REENCRYPT_EVERY} (1 h) on every replica, at most {@code
 * batch} rows per run; a row is only written while it still holds what was read (an enrolment at the same moment
 * wins). When {@link #stale()} is 0 the old key can leave {@code TOTP_PREVIOUS_KEYS}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpKeyRotation {

    static final String METRIC = "northline.crypto.reencrypted";
    static final String TABLE = "auth.totp_secrets";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final MeterRegistry meters;

    @Value("${northline.auth.totp-reencrypt-batch:200}")
    private int batch = 200;

    /** What one run did. */
    public record Outcome(int reencrypted, int failed) {}

    @Scheduled(
            fixedDelayString = "${northline.auth.totp-reencrypt-every:1h}",
            initialDelayString = "${northline.auth.totp-reencrypt-initial-delay:2m}")
    void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("TOTP secret re-encryption skipped: {}", e.toString());
        }
    }

    /** One pass: every secret not under the current key (at most {@code batch}) moves to it. */
    public Outcome run() {
        var current = cipher.keyId();
        var reencrypted = 0;
        var failed = 0;
        for (var row : staleRows(current)) {
            try {
                var opened = cipher.open(row.secret().array(), row.keyId());
                reencrypted += jdbc.sql("""
                                UPDATE auth.totp_secrets SET secret_enc = :enc, key_id = :key
                                 WHERE user_id = :id AND secret_enc = :old""")
                        .param("enc", cipher.encrypt(opened.secret()))
                        .param("key", current)
                        .param("id", row.userId())
                        .param("old", row.secret().array())
                        .update();
            } catch (RuntimeException e) {
                failed++;
                log.warn(
                        "TOTP secret re-encryption: row of {} (key {}) failed: {}",
                        row.userId(),
                        row.keyId(),
                        e.toString());
            }
        }
        meters.counter(METRIC, "table", TABLE, "outcome", "reencrypted").increment(reencrypted);
        meters.counter(METRIC, "table", TABLE, "outcome", "failed").increment(failed);
        if (reencrypted > 0 || failed > 0) {
            log.info("TOTP secret re-encryption: {} moved to key {}, {} failed", reencrypted, current, failed);
        }
        return new Outcome(reencrypted, failed);
    }

    /** Secrets still under another key than the current one (0 = the previous key can go). */
    public int stale() {
        return jdbc.sql("SELECT count(*) FROM auth.totp_secrets WHERE key_id IS DISTINCT FROM :current")
                .param("current", cipher.keyId())
                .query(Integer.class)
                .single();
    }

    /** {@code secret} wraps the stored bytes (a record compares a ByteBuffer by content, an array by reference). */
    private record Row(
            String userId, ByteBuffer secret, @Nullable String keyId) {}

    private List<Row> staleRows(String current) {
        return jdbc.sql("""
                        SELECT user_id, secret_enc, key_id FROM auth.totp_secrets
                         WHERE key_id IS DISTINCT FROM :current ORDER BY user_id LIMIT :limit""")
                .param("current", current)
                .param("limit", batch)
                .query((rs, _) -> new Row(
                        rs.getString("user_id"), ByteBuffer.wrap(rs.getBytes("secret_enc")), rs.getString("key_id")))
                .list();
    }
}
