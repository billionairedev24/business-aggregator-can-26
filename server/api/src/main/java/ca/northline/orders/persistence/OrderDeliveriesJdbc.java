package ca.northline.orders.persistence;

import ca.northline.orders.application.OrderDeliveries;
import ca.northline.shared.JdbcTimes;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link OrderDeliveries} on {@code orders.orders} / {@code order_lines}. */
@Repository
@RequiredArgsConstructor
class OrderDeliveriesJdbc implements OrderDeliveries {

    private final JdbcClient jdbc;

    @Override
    public Optional<Order> lock(String orderId) {
        return jdbc.sql("""
                        select id, customer_id, type, state, delivered_at, confirmed_at
                          from orders.orders where id = :id for update
                        """)
                .param("id", orderId)
                .query((rs, _) -> new Order(
                        rs.getString("id"),
                        rs.getString("customer_id"),
                        Objects.requireNonNullElse(rs.getString("type"), "goods"),
                        Objects.requireNonNullElse(rs.getString("state"), "placed"),
                        JdbcTimes.instant(rs, "delivered_at"),
                        JdbcTimes.instant(rs, "confirmed_at")))
                .optional();
    }

    @Override
    public void markDelivered(String orderId, String proof, Instant at) {
        jdbc.sql("""
                        update orders.orders set state = 'delivered', delivery_proof = :proof,
                               delivered_at = coalesce(delivered_at, :at)
                         where id = :id
                        """)
                .param("proof", proof)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", orderId)
                .update();
    }

    @Override
    public void markConfirmed(String orderId, Instant at) {
        jdbc.sql("""
                        update orders.orders set state = 'confirmed', confirmed_at = :at,
                               delivered_at = coalesce(delivered_at, :at)
                         where id = :id
                        """)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", orderId)
                .update();
    }

    @Override
    public boolean markReturned(String orderId, boolean allLines, Instant at) {
        var moved = jdbc.sql("""
                        update orders.orders set state = 'returned'
                         where id = :id and state not in ('returned', 'refunded', 'cancelled')
                        """).param("id", orderId).update() > 0;
        if (moved) {
            jdbc.sql("""
                            update orders.order_lines set state = 'refunded'
                             where order_id = :id and (:all or age_class is not null)
                            """).param("id", orderId).param("all", allLines).update();
        }
        return moved;
    }

    @Override
    public long restrictedCents(String orderId) {
        return jdbc.sql("""
                        select coalesce(sum(unit_cents * qty), 0) from orders.order_lines
                         where order_id = :id and age_class is not null
                        """).param("id", orderId).query(Long.class).single();
    }

    @Override
    public List<String> escrowLines(String orderId) {
        return jdbc.sql("""
                        select id from orders.order_lines
                         where order_id = :id and coalesce(state, 'pending') <> 'refunded'
                         order by id
                        """)
                .param("id", orderId)
                .query((rs, _) -> rs.getString("id"))
                .list();
    }

    @Override
    public List<String> merchants(String orderId) {
        return jdbc.sql("""
                        select distinct merchant_id from orders.order_lines
                         where order_id = :id and merchant_id is not null
                         order by merchant_id
                        """)
                .param("id", orderId)
                .query((rs, _) -> rs.getString("merchant_id"))
                .list();
    }
}
