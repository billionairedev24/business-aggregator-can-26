package ca.northline.region.persistence;

import ca.northline.region.api.TaxRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the province's current tax profile (gst + pst + hst + qst, stored as fractions such as 0.05; every province's
 * is region data since V130). A province without one is charged the federal GST alone. See docs/DECISIONS.md "S-134".
 */
@Repository
@RequiredArgsConstructor
class TaxRateQueries implements TaxRates {

    /** GST, charged in every province and territory. */
    static final int FEDERAL_GST_BPS = 500;

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
                .map(rate -> rate.movePointRight(4).setScale(0, RoundingMode.HALF_UP).intValueExact())
                .orElse(FEDERAL_GST_BPS);
    }
}
