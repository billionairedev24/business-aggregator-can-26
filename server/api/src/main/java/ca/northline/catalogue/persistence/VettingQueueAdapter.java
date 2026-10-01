package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.VettingQueue;
import ca.northline.shared.Backlog;
import ca.northline.shared.MerchantScope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link VettingQueue} over {@code catalogue.offers} and {@code catalogue.services} (S-91). */
@Repository
@RequiredArgsConstructor
class VettingQueueAdapter implements VettingQueue {

    private final JdbcClient jdbc;

    @Override
    public Backlog flagged(MerchantScope scope) {
        return jdbc.sql("""
                        select count(*) as n, min(submitted_at) as oldest from (
                          select o.submitted_at from catalogue.offers o
                           where o.vetting = 'pending' and cardinality(o.vetting_flags) > 0
                             and (:everyone or o.merchant_id = any(:merchants))
                          union all
                          select s.submitted_at from catalogue.services s
                           where s.vetting = 'pending' and cardinality(s.vetting_flags) > 0
                             and (:everyone or s.merchant_id = any(:merchants))) q
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new Backlog(rs.getLong("n"), Sql.instant(rs, "oldest")))
                .single();
    }
}
