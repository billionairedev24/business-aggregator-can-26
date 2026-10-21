package ca.northline.auth.persistence;

import ca.northline.auth.application.ConsentRecords;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code messaging.consent_records} (the api's V300 table): sign-up grants, appended with the account (S-157). */
@Repository
@RequiredArgsConstructor
class JdbcConsentRecords implements ConsentRecords {

    private final JdbcClient jdbc;

    @Override
    public void append(Grant g) {
        jdbc.sql("""
                        INSERT INTO messaging.consent_records (id, user_id, category, action, basis, at, source,
                               wording_version, language, address_hash, ip_prefix, user_agent_hash)
                        VALUES (:id, :user, :category, 'granted', 'express', :at, :source, :wording, :language,
                               :address, :ip, :ua)
                        """)
                .param("id", g.id())
                .param("user", g.userId())
                .param("category", g.category())
                .param("at", g.at().atOffset(ZoneOffset.UTC))
                .param("source", g.source())
                .param("wording", g.wordingVersion())
                .param("language", g.language())
                .param("address", g.addressHash())
                .param("ip", g.ipPrefix())
                .param("ua", g.userAgentHash())
                .update();
    }
}
