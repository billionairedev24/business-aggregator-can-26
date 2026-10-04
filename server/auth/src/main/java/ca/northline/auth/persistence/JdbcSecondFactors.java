package ca.northline.auth.persistence;

import ca.northline.auth.application.SecondFactors;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.totp_secrets} (encrypted) and {@code auth.backup_codes} (hashed). */
@Repository
@RequiredArgsConstructor
class JdbcSecondFactors implements SecondFactors {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    @Override
    public void saveTotp(String userId, String secret, Instant confirmedAt) {
        jdbc.sql("""
                        INSERT INTO auth.totp_secrets (user_id, secret_enc, key_id, confirmed_at)
                        VALUES (:id, :enc, :key, :at)
                        ON CONFLICT (user_id) DO UPDATE
                           SET secret_enc = excluded.secret_enc, key_id = excluded.key_id,
                               confirmed_at = excluded.confirmed_at, last_used_step = NULL
                        """)
                .param("id", userId)
                .param("enc", cipher.encrypt(secret))
                .param("key", cipher.keyId())
                .param("at", utc(confirmedAt))
                .update();
    }

    @Override
    public Optional<StoredTotp> findTotp(String userId) {
        return jdbc.sql("""
                        SELECT secret_enc, key_id, coalesce(last_used_step, -1) AS last_step FROM auth.totp_secrets
                         WHERE user_id = :id AND confirmed_at IS NOT NULL
                        """)
                .param("id", userId)
                .query((rs, _) -> new StoredTotp(
                        cipher.decrypt(rs.getBytes("secret_enc"), rs.getString("key_id")), rs.getLong("last_step")))
                .optional();
    }

    @Override
    public boolean markTotpUsed(String userId, long step) {
        return jdbc.sql("""
                        UPDATE auth.totp_secrets SET last_used_step = :step
                         WHERE user_id = :id AND (last_used_step IS NULL OR last_used_step < :step)
                        """).param("id", userId).param("step", step).update() == 1;
    }

    @Override
    public void replaceBackupCodes(String userId, List<String> hashes, Instant at) {
        jdbc.sql("DELETE FROM auth.backup_codes WHERE user_id = :id")
                .param("id", userId)
                .update();
        for (var hash : hashes) {
            jdbc.sql("""
                            INSERT INTO auth.backup_codes (id, user_id, code_hash, created_at)
                            VALUES (:id, :user, :hash, :at)
                            """)
                    .param("id", UlidCreator.getMonotonicUlid().toString())
                    .param("user", userId)
                    .param("hash", hash)
                    .param("at", utc(at))
                    .update();
        }
    }

    @Override
    public boolean consumeBackupCode(String userId, String hash, Instant at) {
        return jdbc.sql("""
                        UPDATE auth.backup_codes SET used_at = :at
                         WHERE id = (SELECT id FROM auth.backup_codes
                                      WHERE user_id = :user AND code_hash = :hash AND used_at IS NULL
                                      LIMIT 1 FOR UPDATE)
                        """)
                        .param("user", userId)
                        .param("hash", hash)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
