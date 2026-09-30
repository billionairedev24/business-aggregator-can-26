package ca.northline.auth.persistence;

import ca.northline.auth.application.IssuedRefreshTokens;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link IssuedRefreshTokens} over {@code auth.issued_refresh_tokens} (V024; rows go with their authorization). */
@Repository
@RequiredArgsConstructor
class JdbcIssuedRefreshTokens implements IssuedRefreshTokens {

    private final JdbcClient jdbc;

    @Override
    public void remember(String tokenHash, String authorizationId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO auth.issued_refresh_tokens (token_hash, authorization_id, issued_at, expires_at)
                        SELECT :h, :a, :issued, :expires
                         WHERE EXISTS (SELECT 1 FROM auth.oauth2_authorization WHERE id = :a)
                        ON CONFLICT (token_hash) DO NOTHING
                        """)
                .param("h", tokenHash)
                .param("a", authorizationId)
                .param("issued", issuedAt.atOffset(ZoneOffset.UTC))
                .param("expires", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<String> familyOf(String tokenHash) {
        return jdbc.sql("SELECT authorization_id FROM auth.issued_refresh_tokens WHERE token_hash = :h")
                .param("h", tokenHash)
                .query(String.class)
                .optional();
    }
}
