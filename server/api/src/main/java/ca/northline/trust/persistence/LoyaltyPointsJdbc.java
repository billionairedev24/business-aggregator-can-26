package ca.northline.trust.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.trust.api.LoyaltyPoints;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link LoyaltyPoints} over {@code trust.points_ledger} (V140 added {@code created_at} and the user index). */
@Repository
@RequiredArgsConstructor
class LoyaltyPointsJdbc implements LoyaltyPoints {

    static final int WEEKS = 8;

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public Points of(String userId) {
        var now = clock.instant();
        var balance = jdbc.sql("""
                        select coalesce(sum(delta), 0) from trust.points_ledger
                         where user_id = :u and (expires_at is null or expires_at > :now or delta < 0)
                        """)
                .param("u", userId)
                .param("now", JdbcTimes.ts(now))
                .query(Long.class)
                .single();
        var weekly = new ArrayList<Long>();
        var start = now.minus(Duration.ofDays(7L * WEEKS));
        var earned = jdbc.sql("""
                        select floor(extract(epoch from (created_at - :start)) / 604800)::int as week, sum(delta) as pts
                          from trust.points_ledger
                         where user_id = :u and delta > 0 and created_at > :start and created_at <= :now
                         group by 1
                        """)
                .param("u", userId)
                .param("start", JdbcTimes.ts(start))
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> new long[] {rs.getInt("week"), rs.getLong("pts")})
                .list();
        for (int i = 0; i < WEEKS; i++) {
            weekly.add(0L);
        }
        earned.forEach(w -> {
            var week = (int) Math.min(WEEKS - 1, Math.max(0, w[0]));
            weekly.set(week, weekly.get(week) + w[1]);
        });
        return new Points(Math.max(0, balance), weekly);
    }
}
