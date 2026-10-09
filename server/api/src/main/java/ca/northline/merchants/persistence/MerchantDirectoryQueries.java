package ca.northline.merchants.persistence;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.api.MerchantVerifications;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link MerchantDirectory} and {@link MerchantVerifications}: the queries payments, messaging, food and catalogue
 * used to run against the merchants schema themselves (S-37). {@link ShopDirectory}: the consumer Shop's sellers (S-49).
 */
@Repository
@RequiredArgsConstructor
class MerchantDirectoryQueries implements MerchantDirectory, MerchantVerifications, ShopDirectory {

    private final JdbcClient jdbc;

    @Override
    public Optional<MerchantProfile> profile(String merchantId) {
        return jdbc.sql("""
                        select id, type, tier, status, take_rate_bps, province, city from merchants.merchants where id = :id
                        """)
                .param("id", merchantId)
                .query((rs, _) -> profile(rs))
                .optional();
    }

    @Override
    public Map<String, MerchantProfile> profiles(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select id, type, tier, status, take_rate_bps, province, city from merchants.merchants
                         where id = any(:ids)""")
                .param("ids", merchantIds.toArray(String[]::new))
                .query((rs, _) -> profile(rs))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(MerchantProfile::merchantId, p -> p));
    }

    private static MerchantProfile profile(ResultSet rs) throws SQLException {
        return new MerchantProfile(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("tier"),
                rs.getString("status"),
                rs.getObject("take_rate_bps", Integer.class),
                rs.getString("province"),
                rs.getString("city"));
    }

    private static final String SHOP = """
            select id, display_name, coalesce(tier, 'registered') as tier, city from merchants.merchants
             where status = 'active' and type in ('seller', 'both')
            """;

    @Override
    public List<Shop> shopsIn(String market) {
        return jdbc.sql(SHOP + " and lower(city) = lower(:city) order by display_name, id")
                .param("city", market.strip())
                .query((rs, _) -> shop(rs))
                .list();
    }

    @Override
    public List<Shop> shops(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SHOP + " and id = any(:ids) order by display_name, id")
                .param("ids", merchantIds.toArray(String[]::new))
                .query((rs, _) -> shop(rs))
                .list();
    }

    private static Shop shop(ResultSet rs) throws SQLException {
        return new Shop(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("tier"),
                Objects.requireNonNullElse(rs.getString("city"), ""));
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
