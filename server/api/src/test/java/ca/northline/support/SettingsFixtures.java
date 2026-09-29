package ca.northline.support;

import ca.northline.shared.Ids;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL-level fixtures for the settings &amp; compliance tests (fresh ULIDs, never shared rows). */
public record SettingsFixtures(JdbcClient jdbc) {

    /** A user with contact details and a second factor, as registration leaves them. */
    public String person(String name, @Nullable String email, @Nullable String phone, @Nullable String mfa) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into identity.users (id, display_name, email, phone, mfa_primary, locale, status)
                        values (?, ?, cast(? as citext), ?, ?, 'en-CA', 'active')
                        """).params(id, name, email, phone, mfa).update();
        return id;
    }

    /** A unique email for this run. */
    public static String email(String who) {
        return who + "." + Ids.next().toLowerCase(java.util.Locale.ROOT) + "@example.com";
    }

    public String verification(
            String merchantId,
            String type,
            @Nullable String registry,
            @Nullable String reference,
            String status,
            @Nullable Instant expiresAt,
            @Nullable String checkKey) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.verifications
                               (id, merchant_id, check_type, registry, reference, status, expires_at, check_key, label)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        merchantId,
                        type,
                        registry,
                        reference,
                        status,
                        expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC),
                        checkKey,
                        null)
                .update();
        return id;
    }
}
