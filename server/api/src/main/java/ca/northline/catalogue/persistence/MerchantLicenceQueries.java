package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.LicenceRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Licence check for regulated categories. Reads {@code merchants.verifications} (verified, unexpired licence or
 * registry check for the category's registry). This is a read-only query across the module boundary: the merchants
 * module has no public licence API yet — replace this adapter with a call to it once it exists (docs/DECISIONS.md).
 */
@Repository
@RequiredArgsConstructor
class MerchantLicenceQueries implements LicenceRegistry {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public boolean hasVerifiedLicence(String merchantId, String registry) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from merchants.verifications
                                        where merchant_id = :m and check_type in ('licence', 'registry')
                                          and status = 'verified' and lower(registry) = lower(:registry)
                                          and (expires_at is null or expires_at > :now))
                        """)
                .param("m", merchantId)
                .param("registry", registry)
                .param("now", Sql.ts(clock.instant()))
                .query(Boolean.class)
                .single());
    }
}
