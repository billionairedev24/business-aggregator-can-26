package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.application.EarningsStore;
import ca.northline.shared.JdbcTimes;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link EarningsStore} on {@code fulfilment.courier_earnings} (V433). */
@Repository
@RequiredArgsConstructor
class EarningsJdbc implements EarningsStore {

    private final JdbcClient jdbc;

    @Override
    public boolean insert(Earning e) {
        return jdbc.sql("""
                                insert into fulfilment.courier_earnings (run_id, courier_id, courier_user_id, market,
                                       pickups, dropoffs, failed, collects, returns, run_cents, stops_cents,
                                       total_cents, model, earned_at)
                                values (:run, :courier, :user, :market, :pickups, :dropoffs, :failed, :collects,
                                        :returns, :runCents, :stopsCents, :total, cast(:model as jsonb), :at)
                                on conflict (run_id) do nothing
                                """)
                        .param("run", e.runId())
                        .param("courier", e.courierId())
                        .param("user", e.courierUserId())
                        .param("market", e.market())
                        .param("pickups", e.pickups())
                        .param("dropoffs", e.dropoffs())
                        .param("failed", e.failed())
                        .param("collects", e.collects())
                        .param("returns", e.returns())
                        .param("runCents", e.runCents())
                        .param("stopsCents", e.stopsCents())
                        .param("total", e.totalCents())
                        .param("model", e.model())
                        .param("at", JdbcTimes.ts(e.earnedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update()
                == 1;
    }

    @Override
    public List<Earning> of(String courierUserId, Instant from, Instant to) {
        return jdbc.sql("""
                        select run_id, courier_id, courier_user_id, market, pickups, dropoffs, failed, collects, returns,
                               run_cents, stops_cents, model::text as model, earned_at
                          from fulfilment.courier_earnings
                         where courier_user_id = :u and earned_at >= :from and earned_at < :to
                         order by earned_at desc, run_id
                        """)
                .param("u", courierUserId)
                .param("from", JdbcTimes.ts(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", JdbcTimes.ts(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, _) -> new Earning(
                        rs.getString("run_id"),
                        rs.getString("courier_id"),
                        rs.getString("courier_user_id"),
                        rs.getString("market"),
                        rs.getInt("pickups"),
                        rs.getInt("dropoffs"),
                        rs.getInt("failed"),
                        rs.getInt("collects"),
                        rs.getInt("returns"),
                        rs.getLong("run_cents"),
                        rs.getLong("stops_cents"),
                        rs.getString("model"),
                        JdbcTimes.requiredInstant(rs, "earned_at")))
                .list();
    }
}
