package ca.northline.auth.persistence;

import ca.northline.auth.application.ErasedAccounts;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link ErasedAccounts} over the {@code auth} schema; the erased accounts come from {@code identity.users}. */
@Repository
@RequiredArgsConstructor
class JdbcErasedAccounts implements ErasedAccounts {

    private final JdbcClient jdbc;

    @Override
    @Transactional
    public int purge(int limit) {
        var ids = jdbc.sql("""
                        SELECT u.id FROM identity.users u
                         WHERE u.status = 'erased'
                           AND (EXISTS (SELECT 1 FROM auth.oauth2_authorization a WHERE a.principal_name = u.id)
                             OR EXISTS (SELECT 1 FROM auth.user_entities e WHERE e.name = u.id)
                             OR EXISTS (SELECT 1 FROM auth.totp_secrets t WHERE t.user_id = u.id)
                             OR EXISTS (SELECT 1 FROM auth.backup_codes b WHERE b.user_id = u.id)
                             OR EXISTS (SELECT 1 FROM auth.federated_identities f WHERE f.user_id = u.id)
                             OR EXISTS (SELECT 1 FROM auth.oauth2_authorization_consent c WHERE c.principal_name = u.id))
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, _) -> rs.getString(1))
                .list();
        if (ids.isEmpty()) {
            return 0;
        }
        // authorization_sessions and issued_refresh_tokens go with their authorization (ON DELETE CASCADE),
        // user_credentials with their user entity
        for (var sql : new String[] {
            "DELETE FROM auth.oauth2_authorization WHERE principal_name IN (:ids)",
            "DELETE FROM auth.oauth2_authorization_consent WHERE principal_name IN (:ids)",
            "DELETE FROM auth.user_entities WHERE name IN (:ids)",
            "DELETE FROM auth.totp_secrets WHERE user_id IN (:ids)",
            "DELETE FROM auth.backup_codes WHERE user_id IN (:ids)",
            "DELETE FROM auth.federated_identities WHERE user_id IN (:ids)"
        }) {
            jdbc.sql(sql).param("ids", ids).update();
        }
        return ids.size();
    }
}
