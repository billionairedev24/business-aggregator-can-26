package ca.northline.messaging.persistence;

import ca.northline.messaging.application.MerchantProfiles;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads type and tier from {@code merchants.merchants} — a read-only SQL read across the module boundary because the
 * merchants module exposes no query for it yet (DECISIONS.md › Messaging, help &amp; reviews). Replace with the
 * merchants API once it exists.
 */
@Repository
@RequiredArgsConstructor
class MessagingMerchantProfiles implements MerchantProfiles {

    private final JdbcClient jdbc;

    @Override
    public Optional<Profile> profile(String merchantId) {
        return jdbc.sql("select type, tier from merchants.merchants where id = :id")
                .param("id", merchantId)
                .query((rs, _) -> new Profile(rs.getString("type"), rs.getString("tier")))
                .optional();
    }
}
