package ca.northline.orders.persistence;

import ca.northline.orders.api.MarketplaceOrders;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketplaceOrders} over {@code orders.orders}, {@code order_lines} and {@code delivery_windows} (S-91). */
@Repository
@RequiredArgsConstructor
class MarketplaceOrdersJdbc implements MarketplaceOrders {

    /** The order has a line of a business in scope. */
    private static final String HAS_LINE = """
            (:everyone or exists (select 1 from orders.order_lines sl
                                   where sl.order_id = o.id and sl.merchant_id = any(:merchants)))""";

    private final JdbcClient jdbc;

    @Override
    public List<Long> gmvCents(MerchantScope scope, Instant start, Duration length, int periods) {
        return jdbc.sql("""
                        select g.i, coalesce(sum(l.qty * l.unit_cents), 0) as cents
                          from generate_series(0, :periods - 1) g(i)
                          left join orders.orders o
                                 on o.placed_at >= :start + g.i * make_interval(secs => :seconds)
                                and o.placed_at <  :start + (g.i + 1) * make_interval(secs => :seconds)
                                and o.state is distinct from 'cancelled'
                          left join orders.order_lines l
                                 on l.order_id = o.id and l.state is distinct from 'refunded'
                                and (:everyone or l.merchant_id = any(:merchants))
                         group by g.i order by g.i
                        """)
                .param("periods", periods)
                .param("start", JdbcTimes.ts(start))
                .param("seconds", (double) length.toSeconds())
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> rs.getLong("cents"))
                .list();
    }

    @Override
    public long placed(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from orders.orders o
                         where o.placed_at >= :from and o.placed_at < :to and o.state is distinct from 'cancelled'
                           and %s
                        """.formatted(HAS_LINE))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public OnTime onTime(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) as delivered, count(*) filter (where o.delivered_at <= w.ends_at) as on_time
                          from orders.orders o join orders.delivery_windows w on w.id = o.window_id
                         where o.delivered_at >= :from and o.delivered_at < :to and %s
                        """.formatted(HAS_LINE))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new OnTime(rs.getLong("delivered"), rs.getLong("on_time")))
                .single();
    }

    @Override
    public @Nullable Long averageDeliveryFeeCents(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        select round(avg(coalesce(o.delivery_fee_cents, 0))) as fee from orders.orders o
                         where o.placed_at >= :from and o.placed_at < :to and o.state is distinct from 'cancelled'
                           and (o.window_id is not null or o.fulfilment_mode = 'delivery') and %s
                        """.formatted(HAS_LINE))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> {
                    var fee = rs.getLong("fee");
                    return rs.wasNull() ? null : fee;
                })
                .optional()
                .orElse(null);
    }

    @Override
    public long onRun(MerchantScope scope, String windowId) {
        return jdbc.sql("""
                        select count(*) from orders.orders o
                         where o.window_id = :window and o.state is distinct from 'cancelled' and %s
                        """.formatted(HAS_LINE))
                .param("window", windowId)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }
}
