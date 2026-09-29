package ca.northline.region.persistence;

import ca.northline.region.api.TaxRates;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the province's current tax profile (gst + pst + hst + qst, stored as fractions such as 0.05). Until the regions
 * are seeded, falls back to the statutory rates below (Alberta: GST 5 %). See docs/DECISIONS.md "Operations".
 */
@Repository
@RequiredArgsConstructor
class TaxRateQueries implements TaxRates {

    private static final Map<String, Integer> STATUTORY_BPS = Map.of("AB", 500, "BC", 1200, "ON", 1300, "QC", 1498);

    private final JdbcClient jdbc;

    @Override
    public int bpsFor(String province) {
        return jdbc.sql("""
                        select coalesce(t.gst, 0) + coalesce(t.pst, 0) + coalesce(t.hst, 0) + coalesce(t.qst, 0)
                          from region.regions r join region.tax_profiles t on t.id = r.tax_profile_id
                         where r.province = :province and (t.effective_from is null or t.effective_from <= current_date)
                         order by t.effective_from desc nulls last limit 1
                        """)
                .param("province", province)
                .query(BigDecimal.class)
                .optional()
                .map(rate -> rate.movePointRight(4).intValue())
                .orElseGet(() -> STATUTORY_BPS.getOrDefault(province, 500));
    }
}
