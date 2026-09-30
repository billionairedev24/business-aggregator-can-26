package ca.northline.merchants.persistence;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.api.MerchantVerifications;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link MerchantDirectory} and {@link MerchantVerifications}: the queries payments, messaging, food and catalogue
 * used to run against the merchants schema themselves (S-37).
 */
@Repository
@RequiredArgsConstructor
class MerchantDirectoryQueries implements MerchantDirectory, MerchantVerifications {

    private final JdbcClient jdbc;

    @Override
    public Optional<MerchantProfile> profile(String merchantId) {
        return jdbc.sql("""
                        select id, type, tier, status, take_rate_bps, province from merchants.merchants where id = :id
                        """)
                .param("id", merchantId)
                .query((rs, _) -> new MerchantProfile(
                        rs.getString("id"),
                        rs.getString("type"),
                        rs.getString("tier"),
                        rs.getString("status"),
                        rs.getObject("take_rate_bps", Integer.class),
                        rs.getString("province")))
                .optional();
    }

    @Override
    public boolean hasVerifiedLicence(String merchantId, String registry, Instant at) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from merchants.verifications
                                        where merchant_id = :m and check_type in ('licence', 'registry')
                                          and status = 'verified' and lower(registry) = lower(:registry)
                                          and (expires_at is null or expires_at > :at))
                        """)
                .param("m", merchantId)
                .param("registry", registry)
                .param("at", JdbcTimes.ts(at))
                .query(Boolean.class)
                .single());
    }

    @Override
    public Optional<Evidence> latest(String merchantId, String checkType) {
        return jdbc.sql("""
                        select reference, status, expires_at from merchants.verifications
                         where merchant_id = :m and check_type = :type
                         order by (status = 'verified') desc, updated_at desc limit 1
                        """)
                .param("m", merchantId)
                .param("type", checkType)
                .query((rs, _) -> new Evidence(
                        rs.getString("reference"), rs.getString("status"), JdbcTimes.instant(rs, "expires_at")))
                .optional();
    }
}
