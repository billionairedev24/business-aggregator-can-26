package ca.northline.food.persistence;

import ca.northline.food.application.KitchenMerchantFacts;
import ca.northline.shared.JdbcTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only query across the module boundary ({@code merchants.merchants.status}, {@code merchants.verifications}),
 * like catalogue's licence check: the merchants module has no public API for these yet — replace this adapter with a
 * call to it once it exists (docs/DECISIONS.md › Kitchen).
 */
@Repository
@RequiredArgsConstructor
class KitchenMerchantFactsJdbc implements KitchenMerchantFacts {

    private final JdbcClient jdbc;

    @Override
    public boolean approved(String merchantId) {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists (select 1 from merchants.merchants where id = :m and status = 'active')")
                        .param("m", merchantId)
                        .query(Boolean.class)
                        .single());
    }

    @Override
    public FoodSafety foodSafety(String merchantId) {
        return new FoodSafety(
                evidence(merchantId, "ahs_permit"),
                evidence(merchantId, "food_cert"),
                evidence(merchantId, "inspection"));
    }

    private Evidence evidence(String merchantId, String checkType) {
        return jdbc.sql("""
                        select reference, status, expires_at from merchants.verifications
                         where merchant_id = :m and check_type = :type
                         order by (status = 'verified') desc, updated_at desc limit 1
                        """)
                .param("m", merchantId)
                .param("type", checkType)
                .query((rs, _) -> new Evidence(
                        rs.getString("reference"), rs.getString("status"), JdbcTimes.instant(rs, "expires_at")))
                .optional()
                .orElse(Evidence.NONE);
    }
}
