package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.FleetStatus;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FleetStatus} over {@code fulfilment.couriers}, {@code runs} and {@code stops} (S-91). */
@Repository
@RequiredArgsConstructor
class FleetStatusJdbc implements FleetStatus {

    private final JdbcClient jdbc;

    @Override
    public Fleet now(Instant now, Duration stuckAfter) {
        return jdbc.sql("""
                        with overdue as (
                          select st.run_id, min(st.eta) as eta
                            from fulfilment.stops st join fulfilment.runs r on r.id = st.run_id
                           where r.state in ('loading', 'en_route') and st.arrived_at is null
                             and st.eta < :late
                           group by st.run_id)
                        select (select count(*) from fulfilment.couriers where status = 'on_run') as on_runs,
                               (select count(*) from fulfilment.couriers where status in ('available', 'on_run')) as active,
                               (select count(distinct c.id) from fulfilment.couriers c
                                  join fulfilment.runs r on r.courier_id = c.id
                                 where c.status = 'offline' and r.state in ('loading', 'en_route')) as offline_on_run,
                               (select count(*) from overdue) as stuck,
                               (select min(eta) from overdue) as oldest
                        """)
                .param("late", JdbcTimes.ts(now.minus(stuckAfter)))
                .query((rs, _) -> new Fleet(
                        rs.getLong("on_runs"),
                        rs.getLong("active"),
                        rs.getLong("offline_on_run"),
                        rs.getLong("stuck"),
                        JdbcTimes.instant(rs, "oldest")))
                .single();
    }
}
