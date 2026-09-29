package ca.northline.payments.persistence;

import ca.northline.payments.api.TaxSummary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link TaxSummary} over {@code payments.tax_jurisdiction_totals} (settings &amp; compliance workstream, V082). */
@Repository
@RequiredArgsConstructor
class TaxSummaryQueries implements TaxSummary {

    private final JdbcClient jdbc;

    @Override
    public List<JurisdictionTotal> totals(String merchantId, String period) {
        return jdbc.sql("""
                        select jurisdiction, collected_cents, handling from payments.tax_jurisdiction_totals
                         where merchant_id = :m and period = :p
                         order by (jurisdiction = 'platform_fee_gst'), collected_cents desc, jurisdiction
                        """)
                .param("m", merchantId)
                .param("p", period)
                .query((rs, _) -> new JurisdictionTotal(
                        rs.getString("jurisdiction"), rs.getLong("collected_cents"), rs.getString("handling")))
                .list();
    }
}
