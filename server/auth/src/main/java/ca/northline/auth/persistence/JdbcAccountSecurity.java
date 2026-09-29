package ca.northline.auth.persistence;

import ca.northline.auth.application.AccountSecurity;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AccountSecurity} over the WebAuthn tables, TOTP secrets, backup codes and {@code identity.sessions}. */
@Repository
@RequiredArgsConstructor
class JdbcAccountSecurity implements AccountSecurity {

    private final JdbcClient jdbc;

    @Override
    public List<Passkey> passkeys(String userId) {
        return jdbc.sql("""
                        SELECT c.credential_id, c.label, c.created, c.last_used
                          FROM auth.user_credentials c
                          JOIN auth.user_entities e ON e.id = c.user_entity_user_id
                         WHERE e.name = :u
                         ORDER BY c.created NULLS LAST, c.credential_id
                        """)
                .param("u", userId)
                .query((rs, _) -> new Passkey(
                        rs.getString("credential_id"),
                        rs.getString("label"),
                        instant(rs.getObject("created", OffsetDateTime.class)),
                        instant(rs.getObject("last_used", OffsetDateTime.class))))
                .list();
    }

    @Override
    public @Nullable Instant authenticatorSince(String userId) {
        return jdbc.sql("SELECT confirmed_at FROM auth.totp_secrets WHERE user_id = :u AND confirmed_at IS NOT NULL")
                .param("u", userId)
                .query((rs, _) -> instant(rs.getObject("confirmed_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
    }

    @Override
    public BackupCodes backupCodes(String userId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE used_at IS NULL) AS remaining, max(created_at) AS issued
                          FROM auth.backup_codes WHERE user_id = :u
                        """)
                .param("u", userId)
                .query((rs, _) ->
                        new BackupCodes(rs.getInt("remaining"), instant(rs.getObject("issued", OffsetDateTime.class))))
                .single();
    }

    @Override
    public List<SignIn> recentSignIns(String userId, int limit) {
        return jdbc.sql("""
                        SELECT id, device, city, method, created_at, last_seen_at FROM identity.sessions
                         WHERE user_id = :u AND revoked_at IS NULL
                         ORDER BY created_at DESC, id DESC LIMIT :limit
                        """)
                .param("u", userId)
                .param("limit", limit)
                .query((rs, _) -> new SignIn(
                        rs.getString("id"),
                        rs.getString("device"),
                        rs.getString("city"),
                        rs.getString("method"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("last_seen_at", OffsetDateTime.class))))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
