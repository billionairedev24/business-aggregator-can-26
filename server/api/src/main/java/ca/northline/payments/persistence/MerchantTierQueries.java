package ca.northline.payments.persistence;

import ca.northline.payments.application.MerchantTiers;
import ca.northline.payments.domain.Tier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code merchants.merchants.tier / take_rate_bps} (read-only; the merchants module has no public query for them
 * yet — see DECISIONS.md, Finance workstream).
 */
@Repository
@RequiredArgsConstructor
class MerchantTierQueries implements MerchantTiers {

    private final JdbcClient jdbc;

    @Override
    public Rate rateOf(String merchantId) {
        return jdbc.sql("select tier, take_rate_bps from merchants.merchants where id = :id")
                .param("id", merchantId)
                .query((rs, _) -> {
                    var tier = Tier.of(rs.getString("tier"));
                    var bps = rs.getObject("take_rate_bps", Integer.class);
                    return new Rate(tier, bps == null ? tier.takeRateBps() : bps);
                })
                .optional()
                .orElse(new Rate(Tier.REGISTERED, Tier.REGISTERED.takeRateBps()));
    }
}
