package ca.northline.auth.persistence;

import static ca.northline.auth.persistence.JdbcPendingAccounts.instant;
import static ca.northline.auth.persistence.JdbcPendingAccounts.nullableInstant;
import static ca.northline.auth.persistence.JdbcPendingAccounts.utc;

import ca.northline.auth.application.EmailCodes;
import ca.northline.auth.domain.App;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code auth.email_codes}: hashed one-time codes and link tokens. */
@Repository
@RequiredArgsConstructor
class JdbcEmailCodes implements EmailCodes {

    private static final String SELECT = """
            SELECT id, purpose, subject_id, app, code_hash, token_hash, failed_attempts, created_at, expires_at, used_at
              FROM auth.email_codes
            """;

    private final JdbcClient jdbc;

    @Override
    public void issue(StoredCode c) {
        // Earlier unused codes of the subject stop working: only the newest email counts.
        jdbc.sql("""
                        UPDATE auth.email_codes SET used_at = :at
                         WHERE subject_id = :subject AND purpose = :purpose AND used_at IS NULL""")
                .param("subject", c.subjectId())
                .param("purpose", c.purpose().code())
                .param("at", utc(c.createdAt()))
                .update();
        jdbc.sql("""
                        INSERT INTO auth.email_codes (id, purpose, subject_id, app, code_hash, token_hash,
                               failed_attempts, created_at, expires_at)
                        VALUES (:id, :purpose, :subject, :app, :code, :token, 0, :at, :expires)
                        """)
                .param("id", c.id())
                .param("purpose", c.purpose().code())
                .param("subject", c.subjectId())
                .param("app", c.app().code())
                .param("code", c.codeHash())
                .param("token", c.tokenHash())
                .param("at", utc(c.createdAt()))
                .param("expires", utc(c.expiresAt()))
                .update();
    }

    @Override
    public Optional<StoredCode> latest(String subjectId, Purpose purpose) {
        return jdbc.sql(SELECT + " WHERE subject_id = :subject AND purpose = :purpose ORDER BY created_at DESC, id DESC"
                        + " LIMIT 1")
                .param("subject", subjectId)
                .param("purpose", purpose.code())
                .query(JdbcEmailCodes::map)
                .optional();
    }

    @Override
    public Optional<StoredCode> byTokenHash(String tokenHash) {
        return jdbc.sql(SELECT + " WHERE token_hash = :token")
                .param("token", tokenHash)
                .query(JdbcEmailCodes::map)
                .optional();
    }

    @Override
    public int failed(String id) {
        return jdbc.sql("""
                        UPDATE auth.email_codes SET failed_attempts = failed_attempts + 1 WHERE id = :id
                        RETURNING failed_attempts""").param("id", id).query(Integer.class).optional().orElse(0);
    }

    @Override
    public boolean use(String id, Instant at) {
        return jdbc.sql("UPDATE auth.email_codes SET used_at = :at WHERE id = :id AND used_at IS NULL")
                        .param("id", id)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    private static StoredCode map(ResultSet rs, int row) throws SQLException {
        var purpose = rs.getString("purpose");
        return new StoredCode(
                rs.getString("id"),
                Arrays.stream(Purpose.values())
                        .filter(p -> p.code().equals(purpose))
                        .findFirst()
                        .orElseThrow(),
                rs.getString("subject_id"),
                App.of(rs.getString("app")).orElseThrow(),
                rs.getString("code_hash"),
                rs.getString("token_hash"),
                rs.getInt("failed_attempts"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                nullableInstant(rs, "used_at"));
    }
}
