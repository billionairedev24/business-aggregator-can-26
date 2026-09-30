package ca.northline.auth.persistence;

import ca.northline.auth.application.FederatedIdentities;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FederatedIdentities} on {@code auth.federated_identities} (V022). */
@Repository
@RequiredArgsConstructor
class JdbcFederatedIdentities implements FederatedIdentities {

    private final JdbcClient jdbc;

    @Override
    public Optional<String> userOf(String provider, String subject) {
        return jdbc.sql("SELECT user_id FROM auth.federated_identities WHERE provider = :p AND subject = :s")
                .param("p", provider)
                .param("s", subject)
                .query((rs, _) -> rs.getString("user_id"))
                .optional();
    }

    @Override
    public void link(
            String provider, String subject, String userId, @Nullable String email, boolean privateRelay, Instant at) {
        jdbc.sql("""
                        INSERT INTO auth.federated_identities
                               (provider, subject, user_id, email, private_relay, linked_at, last_used_at)
                        VALUES (:p, :s, :u, :email, :relay, :at, :at)
                        ON CONFLICT (provider, subject) DO NOTHING
                        """)
                .param("p", provider)
                .param("s", subject)
                .param("u", userId)
                .param("email", email)
                .param("relay", privateRelay)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public void used(String provider, String subject, @Nullable String email, boolean privateRelay, Instant at) {
        jdbc.sql("""
                        UPDATE auth.federated_identities
                           SET last_used_at = :at, email = coalesce(:email, email), private_relay = :relay
                         WHERE provider = :p AND subject = :s
                        """)
                .param("p", provider)
                .param("s", subject)
                .param("email", email)
                .param("relay", privateRelay)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }
}
