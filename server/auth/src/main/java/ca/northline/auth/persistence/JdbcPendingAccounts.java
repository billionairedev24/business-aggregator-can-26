package ca.northline.auth.persistence;

import ca.northline.auth.application.PendingAccounts;
import ca.northline.auth.domain.App;
import ca.northline.auth.domain.SignupConsent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code auth.pending_accounts} (one per address) and the clean-up of expired ones with their email codes. */
@Repository
@RequiredArgsConstructor
class JdbcPendingAccounts implements PendingAccounts {

    private static final String SELECT = """
            SELECT id, email::text AS email, first_name, last_name, phone, password_hash, locale, app, terms_version,
                   terms_accepted_at, terms_language, terms_english_requested_at, expires_at,
                   marketing_consents::text AS marketing_consents
              FROM auth.pending_accounts
            """;

    private static final TypeReference<List<SignupConsent>> CONSENTS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    @Override
    public String save(PendingAccount a) {
        return jdbc.sql("""
                        INSERT INTO auth.pending_accounts (id, email, first_name, last_name, phone, password_hash,
                               locale, app, terms_version, terms_accepted_at, terms_language,
                               terms_english_requested_at, created_at, expires_at, marketing_consents)
                        VALUES (:id, CAST(:email AS citext), :first, :last, :phone, :hash, :locale, :app, :terms,
                               :termsAt, :termsLanguage, :english, :termsAt, :expires, CAST(:consents AS jsonb))
                        ON CONFLICT (email) DO UPDATE
                           SET first_name = excluded.first_name, last_name = excluded.last_name,
                               phone = excluded.phone, password_hash = excluded.password_hash,
                               locale = excluded.locale, app = excluded.app, terms_version = excluded.terms_version,
                               terms_accepted_at = excluded.terms_accepted_at,
                               terms_language = excluded.terms_language,
                               terms_english_requested_at = excluded.terms_english_requested_at,
                               expires_at = excluded.expires_at,
                               marketing_consents = excluded.marketing_consents
                        RETURNING id
                        """)
                .param("id", a.id())
                .param("email", a.email())
                .param("first", a.firstName())
                .param("last", a.lastName())
                .param("phone", a.phone())
                .param("hash", a.passwordHash())
                .param("locale", a.locale())
                .param("app", a.app().code())
                .param("terms", a.termsVersion())
                .param("termsAt", utc(a.termsAcceptedAt()))
                .param("termsLanguage", a.termsLanguage())
                .param("english", a.termsEnglishRequestedAt() == null ? null : utc(a.termsEnglishRequestedAt()))
                .param("expires", utc(a.expiresAt()))
                .param("consents", json.writeValueAsString(a.marketingConsents()))
                .query(String.class)
                .single();
    }

    @Override
    public Optional<PendingAccount> findById(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<PendingAccount> findByEmail(String email) {
        return jdbc.sql(SELECT + " WHERE email = CAST(:email AS citext)")
                .param("email", email.strip())
                .query(this::map)
                .optional();
    }

    @Override
    public void delete(String id) {
        jdbc.sql("DELETE FROM auth.pending_accounts WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Override
    @Transactional
    public int purgeExpired(Instant at) {
        jdbc.sql("DELETE FROM auth.email_codes WHERE expires_at < :at")
                .param("at", utc(at))
                .update();
        return jdbc.sql("DELETE FROM auth.pending_accounts WHERE expires_at < :at")
                .param("at", utc(at))
                .update();
    }

    private PendingAccount map(ResultSet rs, int row) throws SQLException {
        return new PendingAccount(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("locale"),
                App.of(rs.getString("app")).orElseThrow(),
                rs.getString("terms_version"),
                instant(rs, "terms_accepted_at"),
                rs.getString("terms_language"),
                nullableInstant(rs, "terms_english_requested_at"),
                instant(rs, "expires_at"),
                json.readValue(rs.getString("marketing_consents"), CONSENTS));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
