package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.ListingReadiness;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ListingReadiness} over {@code catalogue.offers} and {@code catalogue.services}. */
@Repository
@RequiredArgsConstructor
class ListingReadinessQueries implements ListingReadiness {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Counts> counts(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select merchant_id, count(*) as total,
                               count(*) filter (where vetting in ('pending', 'approved')) as submitted,
                               count(*) filter (where vetting = 'approved' and coalesce(status, 'live') = 'live') as live
                          from (select merchant_id, vetting, status from catalogue.offers where merchant_id = any(:m)
                                union all
                                select merchant_id, vetting, status from catalogue.services where merchant_id = any(:m)) l
                         group by merchant_id
                        """)
                .param("m", merchantIds.toArray(String[]::new))
                .query((rs, _) -> Map.entry(
                        rs.getString("merchant_id"),
                        new Counts(rs.getInt("total"), rs.getInt("submitted"), rs.getInt("live"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
