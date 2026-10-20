package ca.northline.auth.persistence;

import ca.northline.auth.application.Passwords;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.passwords}: one Argon2id hash per account. */
@Repository
@RequiredArgsConstructor
class JdbcPasswords implements Passwords {

    private final JdbcClient jdbc;

    @Override
    public Optional<String> hashOf(String userId) {
        return jdbc.sql("SELECT hash FROM auth.passwords WHERE user_id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public void save(String userId, String hash, Instant at) {
        jdbc.sql("""
                        INSERT INTO auth.passwords (user_id, hash, changed_at) VALUES (:id, :hash, :at)
                        ON CONFLICT (user_id) DO UPDATE SET hash = excluded.hash, changed_at = excluded.changed_at
                        """)
                .param("id", userId)
                .param("hash", hash)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }
}
