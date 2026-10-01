package ca.northline.orders.persistence;

import ca.northline.orders.api.OrderMonitor;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.sql.Array;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link OrderMonitor} over {@code orders.orders}, {@code order_lines} and {@code delivery_windows} (S-81). */
@Repository
@RequiredArgsConstructor
class OrderMonitorJdbc implements OrderMonitor {

    private final JdbcClient jdbc;

    @Override
    public List<MonitoredOrder> orders(MerchantScope scope, Instant since, @Nullable String ref, int limit) {
        return jdbc.sql("""
                        select o.id, o.ref, o.type, o.state, o.customer_id, o.placed_at, o.delivered_at,
                               w.ends_at as window_ends_at,
                               coalesce(o.subtotal_cents, 0) + coalesce(o.delivery_fee_cents, 0)
                                 + coalesce(o.service_fee_cents, 0) + coalesce(o.tax_cents, 0)
                                 + coalesce(o.tip_cents, 0) as total_cents,
                               (select array_agg(m.merchant_id order by m.first)
                                  from (select l.merchant_id, min(l.id) as first from orders.order_lines l
                                         where l.order_id = o.id group by l.merchant_id) m) as merchant_ids,
                               exists (select 1 from orders.order_lines l
                                        where l.order_id = o.id
                                          and (l.issue_note is not null or l.state in ('short', 'refunded'))) as issue
                          from orders.orders o
                          left join orders.delivery_windows w on w.id = o.window_id
                         where (o.placed_at >= :since
                                or o.state not in ('delivered', 'confirmed', 'refunded', 'cancelled'))
                           and (:ref = '' or upper(o.ref) like :ref || '%')
                           and (:everyone or exists (select 1 from orders.order_lines sl
                                                      where sl.order_id = o.id and sl.merchant_id = any(:merchants)))
                         order by o.placed_at desc, o.id desc
                         limit :limit
                        """)
                .param("since", JdbcTimes.ts(since))
                .param("ref", ref == null ? "" : ref.strip().toUpperCase(Locale.ROOT))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("limit", limit)
                .query((rs, _) -> new MonitoredOrder(
                        rs.getString("id"),
                        rs.getString("ref"),
                        rs.getString("type"),
                        rs.getString("state"),
                        rs.getString("customer_id"),
                        ids(rs.getArray("merchant_ids")),
                        rs.getLong("total_cents"),
                        JdbcTimes.requiredInstant(rs, "placed_at"),
                        JdbcTimes.instant(rs, "window_ends_at"),
                        JdbcTimes.instant(rs, "delivered_at"),
                        rs.getBoolean("issue")))
                .list();
    }

    @Override
    public java.util.Map<String, Sales> salesByMerchant(
            java.util.Collection<String> merchantIds, Instant from, Instant to) {
        if (merchantIds.isEmpty()) {
            return java.util.Map.of();
        }
        var out = new java.util.HashMap<String, Sales>();
        jdbc.sql("""
                        select l.merchant_id, coalesce(sum(l.qty * l.unit_cents) filter (where l.state is distinct from 'refunded'), 0) as gmv,
                               count(distinct o.id) as orders
                          from orders.order_lines l join orders.orders o on o.id = l.order_id
                         where l.merchant_id = any(:ids) and o.placed_at >= :from and o.placed_at < :to
                           and o.state is distinct from 'cancelled'
                         group by l.merchant_id
                        """)
                .param("ids", merchantIds.toArray(String[]::new))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) ->
                        out.put(rs.getString("merchant_id"), new Sales(rs.getLong("gmv"), rs.getLong("orders"))))
                .list();
        return java.util.Map.copyOf(out);
    }

    private static List<String> ids(@Nullable Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
