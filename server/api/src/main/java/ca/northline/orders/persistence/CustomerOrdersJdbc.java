package ca.northline.orders.persistence;

import ca.northline.orders.api.CustomerOrders;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CustomerOrders} over {@code orders.orders}, its lines and the pooled run's window. */
@Repository
@RequiredArgsConstructor
class CustomerOrdersJdbc implements CustomerOrders {

    private final JdbcClient jdbc;

    @Override
    public List<OrderSummary> recent(String customerId, int limit) {
        return query(customerId, null, limit);
    }

    @Override
    public Optional<OrderDetail> detail(String customerId, String orderId) {
        return query(customerId, orderId, 1).stream().findFirst().map(order -> new OrderDetail(
                order,
                jdbc.sql("""
                                select id, coalesce(merchant_id, '') as merchant_id, coalesce(title, 'Item') as title, qty,
                                       coalesce(unit_cents, 0) as unit
                                  from orders.order_lines where order_id = :o order by id
                                """)
                        .param("o", orderId)
                        .query((rs, _) -> new Line(
                                rs.getString("id"),
                                rs.getString("merchant_id"),
                                rs.getString("title"),
                                rs.getInt("qty"),
                                rs.getLong("unit")))
                        .list()));
    }

    private List<OrderSummary> query(String customerId, @Nullable String orderId, int limit) {
        return jdbc.sql("""
                        select o.id, o.ref, coalesce(o.type, 'goods') as type, coalesce(o.state, 'placed') as state,
                               case when o.type = 'food' then o.fulfilment_mode else o.delivery_kind end as delivery,
                               coalesce(o.subtotal_cents, 0) + coalesce(o.delivery_fee_cents, 0)
                                 + coalesce(o.service_fee_cents, 0) + coalesce(o.tax_cents, 0)
                                 + coalesce(o.tip_cents, 0) as total,
                               o.placed_at, o.delivered_at, w.starts_at as window_start, w.ends_at as window_end,
                               coalesce(o.customer_eta, o.scheduled_for) as eta,
                               coalesce((select array_agg(m.merchant_id order by m.first)
                                           from (select merchant_id, min(id) as first from orders.order_lines
                                                  where order_id = o.id and merchant_id is not null
                                                  group by merchant_id) m), '{}') as merchants,
                               coalesce((select array_agg(l.id order by l.id) from orders.order_lines l
                                          where l.order_id = o.id), '{}') as lines,
                               coalesce((select sum(l.qty) from orders.order_lines l where l.order_id = o.id), 0) as items
                          from orders.orders o
                          left join orders.delivery_windows w on w.id = o.window_id
                         where o.customer_id = :c and (cast(:o as text) is null or o.id = :o)
                         order by o.placed_at desc, o.id desc
                         limit :limit
                        """)
                .param("c", customerId)
                .param("o", orderId)
                .param("limit", limit)
                .query((rs, _) -> new OrderSummary(
                        rs.getString("id"),
                        rs.getString("ref"),
                        rs.getString("type"),
                        rs.getString("state"),
                        rs.getString("delivery"),
                        rs.getLong("total"),
                        JdbcTimes.requiredInstant(rs, "placed_at"),
                        JdbcTimes.instant(rs, "delivered_at"),
                        JdbcTimes.instant(rs, "window_start"),
                        JdbcTimes.instant(rs, "window_end"),
                        JdbcTimes.instant(rs, "eta"),
                        strings(rs.getArray("merchants")),
                        strings(rs.getArray("lines")),
                        rs.getInt("items")))
                .list();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
