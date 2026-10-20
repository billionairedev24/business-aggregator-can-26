package ca.northline.auth.persistence;

import ca.northline.auth.application.UserAccount;
import ca.northline.auth.application.UserAccounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code identity.users} (+ {@code merchants.merchant_members}, {@code identity.platform_roles}) via JdbcClient. */
@Repository
@RequiredArgsConstructor
class JdbcUserAccounts implements UserAccounts {

    private static final String SELECT = """
            SELECT id, first_name, last_name, display_name, email::text AS email, phone, locale, mfa_primary,
                   coalesce(status, 'active') AS status, created_at,
                   phone_verified_at IS NOT NULL AS phone_verified
              FROM identity.users
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<UserAccount> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(JdbcUserAccounts::map)
                .optional();
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql(SELECT + " WHERE email = CAST(:email AS citext)")
                .param("email", email.trim())
                .query(JdbcUserAccounts::map)
                .optional();
    }

    @Override
    public Optional<UserAccount> findByPhone(String e164) {
        return jdbc.sql(SELECT + " WHERE phone = :phone AND phone_verified_at IS NOT NULL")
                .param("phone", e164)
                .query(JdbcUserAccounts::map)
                .optional();
    }

    @Override
    public boolean emailInUse(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM identity.users WHERE email = CAST(:email AS citext))")
                .param("email", email.trim())
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean phoneInUse(String e164) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM identity.users
                                        WHERE phone = :phone AND phone_verified_at IS NOT NULL)""").param("phone", e164).query(Boolean.class).single();
    }

    @Override
    public boolean anyPhone(String e164) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM identity.users WHERE phone = :phone)")
                .param("phone", e164)
                .query(Boolean.class)
                .single();
    }

    @Override
    public void createWithEmail(EmailAccount a) {
        jdbc.sql("""
                        INSERT INTO identity.users (id, phone, email, display_name, first_name, last_name, locale,
                               mfa_primary, status, phone_verified_at, email_verified_at, terms_version,
                               terms_accepted_at, terms_language, terms_english_requested_at, created_at, updated_at)
                        VALUES (:id, :phone, CAST(:email AS citext), :displayName, :firstName, :lastName, :locale,
                               NULL, 'active', NULL, :at, :terms, :termsAt, :termsLanguage, :englishRequestedAt,
                               :at, :at)
                        """)
                .param("id", a.id())
                .param("phone", a.phone())
                .param("email", a.email())
                .param("displayName", (a.firstName() + " " + a.lastName()).trim())
                .param("firstName", a.firstName())
                .param("lastName", a.lastName())
                .param("locale", a.locale())
                .param("terms", a.termsVersion())
                .param("termsAt", a.termsAcceptedAt().atOffset(ZoneOffset.UTC))
                .param("termsLanguage", a.termsLanguage())
                .param(
                        "englishRequestedAt",
                        a.termsEnglishRequestedAt() == null
                                ? null
                                : a.termsEnglishRequestedAt().atOffset(ZoneOffset.UTC))
                .param("at", a.at().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public void emailVerified(String userId, Instant at) {
        jdbc.sql("""
                        UPDATE identity.users SET email_verified_at = coalesce(email_verified_at, :at), updated_at = :at
                         WHERE id = :id""")
                .param("id", userId)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public void releaseUnverifiedPhone(String e164) {
        jdbc.sql("UPDATE identity.users SET phone = NULL WHERE phone = :phone AND phone_verified_at IS NULL")
                .param("phone", e164)
                .update();
    }

    @Override
    public void create(NewAccount a) {
        jdbc.sql("""
                        INSERT INTO identity.users (id, phone, email, display_name, first_name, last_name, locale,
                               mfa_primary, status, phone_verified_at, terms_version, terms_accepted_at,
                               terms_language, terms_english_requested_at, created_at, updated_at)
                        VALUES (:id, :phone, CAST(:email AS citext), :displayName, :firstName, :lastName, :locale,
                               :mfa, 'active', :at, :terms, :at, :termsLanguage, :englishRequestedAt, :at, :at)
                        """)
                .param("id", a.id())
                .param("phone", a.phone())
                .param("email", a.email())
                .param("displayName", (a.firstName() + " " + a.lastName()).trim())
                .param("firstName", a.firstName())
                .param("lastName", a.lastName())
                .param("locale", a.locale())
                .param("mfa", a.mfaPrimary())
                .param("terms", a.termsVersion())
                .param("termsLanguage", a.termsLanguage())
                .param(
                        "englishRequestedAt",
                        a.termsEnglishRequestedAt() == null
                                ? null
                                : a.termsEnglishRequestedAt().atOffset(ZoneOffset.UTC))
                .param("at", a.at().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public List<String> merchantIds(String userId) {
        return jdbc.sql("SELECT merchant_id FROM merchants.merchant_members WHERE user_id = :id ORDER BY merchant_id")
                .param("id", userId)
                .query((rs, _) -> Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public List<String> platformRoles(String userId) {
        return jdbc.sql("SELECT role FROM identity.platform_roles WHERE user_id = :id ORDER BY role")
                .param("id", userId)
                .query((rs, _) -> Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    private static UserAccount map(ResultSet rs, int row) throws SQLException {
        return new UserAccount(
                rs.getString("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("locale"),
                rs.getString("mfa_primary"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("phone_verified"));
    }
}
