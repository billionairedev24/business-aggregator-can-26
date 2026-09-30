package ca.northline.orders.persistence;

import ca.northline.orders.application.DeliveryWindowStore;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code orders.delivery_windows} as pooled runs, with the number of households already on each (S-49). */
@Repository
@RequiredArgsConstructor
class DeliveryWindowsJdbc implements DeliveryWindowStore {

    private static final String SELECT = """
            select w.id, w.market, w.slot, w.run_label, w.starts_at, w.ends_at, w.cutoff_at,
                   (select count(distinct o.customer_id) from orders.orders o
                     where o.window_id = w.id and o.state not in ('cancelled', 'refunded')) as households
              from orders.delivery_windows w
            """;

    private final JdbcClient jdbc;

    @Override
    public void ensure(String market, String slot, Instant startsAt, Instant endsAt, Instant packBy, int capacity) {
        jdbc.sql("""
                        insert into orders.delivery_windows
                               (id, market, slot, starts_at, ends_at, cutoff_at, capacity, run_label)
                        select :id, :market, :slot, :starts, :ends, :cutoff, :capacity,
                               'R-' || nextval('orders.run_label_seq')
                         where not exists (select 1 from orders.delivery_windows
                                            where starts_at = :starts and (market is null or market = :market))
                        on conflict (market, starts_at) where market is not null do nothing
                        """)
                .param("id", Ids.next())
                .param("market", market)
                .param("slot", slot)
                .param("starts", JdbcTimes.ts(startsAt))
                .param("ends", JdbcTimes.ts(endsAt))
                .param("cutoff", JdbcTimes.ts(packBy))
                .param("capacity", capacity)
                .update();
    }

    @Override
    public List<Window> between(String market, Instant from, Instant to) {
        return jdbc
                .sql(SELECT + """
                         where (w.market = :market or w.market is null)
                           and w.starts_at >= :from and w.starts_at < :to and w.cutoff_at is not null
                         order by w.starts_at, w.market nulls first
                        """)
                .param("market", market)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> window(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(Window::startsAt, w -> w, (first, _) -> first, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    @Override
    public Optional<Window> byId(String windowId) {
        return jdbc.sql(SELECT + " where w.id = :id")
                .param("id", windowId)
                .query((rs, _) -> window(rs))
                .optional();
    }

    private static Window window(ResultSet rs) throws SQLException {
        var endsAt = JdbcTimes.instant(rs, "ends_at");
        var startsAt = JdbcTimes.requiredInstant(rs, "starts_at");
        return new Window(
                rs.getString("id"),
                rs.getString("market"),
                rs.getString("slot"),
                rs.getString("run_label"),
                startsAt,
                endsAt == null ? startsAt : endsAt,
                JdbcTimes.requiredInstant(rs, "cutoff_at"),
                rs.getInt("households"));
    }
}
