package ca.northline.payments.persistence;

import ca.northline.payments.api.DisputeCounts;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link DisputeCounts} over {@code payments.disputes} (S-82). */
@Repository
@RequiredArgsConstructor
class DisputeCountsJdbc implements DisputeCounts {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Long> opened(Collection<String> merchantIds, Instant from, Instant to) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        var out = new HashMap<String, Long>();
        jdbc.sql("""
                        select d.merchant_id, count(*) as n from payments.disputes d
                         where d.merchant_id = any(:ids) and d.opened_at >= :from and d.opened_at < :to
                         group by d.merchant_id
                        """)
                .param("ids", merchantIds.toArray(String[]::new))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> out.put(rs.getString("merchant_id"), rs.getLong("n")))
                .list();
        return Map.copyOf(out);
    }
}
