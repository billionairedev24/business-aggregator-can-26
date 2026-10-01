package ca.northline.trust.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import ca.northline.trust.api.TrustQueues;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link TrustQueues} over {@code trust.flags} and {@code trust.quality_scores} (S-91). */
@Repository
@RequiredArgsConstructor
class TrustQueueQueries implements TrustQueues {

    private final JdbcClient jdbc;

    @Override
    public Flags openFlags(MerchantScope scope) {
        return jdbc.sql("""
                        select count(*) as n, min(f.created_at) as oldest,
                               bool_or(f.rule = 'off_platform_payment') as off_platform
                          from trust.flags f
                         where coalesce(f.state, 'open') = 'open'
                           and (:everyone or f.merchant_id = any(:merchants)
                                or (f.target_type = 'merchant' and f.target_id = any(:merchants)))
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) ->
                        new Flags(rs.getLong("n"), JdbcTimes.instant(rs, "oldest"), rs.getBoolean("off_platform")))
                .single();
    }

    @Override
    public long belowFloor(MerchantScope scope, int floor) {
        return jdbc.sql("""
                        select count(*) from (
                          select distinct on (q.merchant_id) q.merchant_id, q.score from trust.quality_scores q
                           where (:everyone or q.merchant_id = any(:merchants))
                           order by q.merchant_id, q.date desc) latest
                         where latest.score < :floor
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("floor", floor)
                .query(Long.class)
                .single();
    }
}
