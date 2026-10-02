package ca.northline.identity.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.identity.api.PrivacyAccounts;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link PrivacyAccounts} over {@code identity.users}, {@code identity.sessions}, passkeys and platform roles. */
@Repository
@RequiredArgsConstructor
class PrivacyAccountsJdbc implements PrivacyAccounts {

    private final JdbcClient jdbc;

    @Override
    public Optional<Holder> holder(String userId) {
        return jdbc.sql("""
                        select id, email::text as email, phone, coalesce(locale, 'en-CA') as locale,
                               coalesce(status, 'active') as status, erasure_requested_at
                          from identity.users where id = :id
                        """)
                .param("id", userId)
                .query((rs, _) -> new Holder(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("locale"),
                        rs.getString("status"),
                        instant(rs, "erasure_requested_at")))
                .optional();
    }

    @Override
    public Optional<String> find(String emailOrPhone) {
        var value = emailOrPhone.strip();
        return jdbc.sql("""
                        select id from identity.users
                         where (email = cast(:v as citext) or phone = :v) and coalesce(status, 'active') <> 'erased'
                         order by created_at limit 1
                        """).param("v", value).query(String.class).optional();
    }

    @Override
    public void erasureRequested(String userId, @Nullable Instant at) {
        jdbc.sql("update identity.users set erasure_requested_at = :at, updated_at = now() where id = :u")
                .param("at", ts(at))
                .param("u", userId)
                .update();
    }

    @Override
    @Transactional
    public void close(String userId, Instant at) {
        jdbc.sql("""
                        update identity.users set status = 'erased', erased_at = coalesce(erased_at, :at), updated_at = now()
                         where id = :u
                        """)
                .param("at", Objects.requireNonNull(ts(at)))
                .param("u", userId)
                .update();
        jdbc.sql("""
                        update identity.sessions set revoked_at = :at, revoke_reason = 'erased'
                         where user_id = :u and revoked_at is null
                        """)
                .param("at", Objects.requireNonNull(ts(at)))
                .param("u", userId)
                .update();
        jdbc.sql("delete from identity.passkeys where user_id = :u")
                .param("u", userId)
                .update();
        jdbc.sql("delete from identity.platform_roles where user_id = :u")
                .param("u", userId)
                .update();
    }
}
