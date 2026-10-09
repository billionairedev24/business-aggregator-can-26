package ca.northline.orders.persistence;

import ca.northline.orders.application.TrackingStore;
import ca.northline.shared.JdbcTimes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link TrackingStore}: the customer's own order ({@code customer_id} must match) and its lines. */
@Repository
@RequiredArgsConstructor
class TrackingJdbc implements TrackingStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Header> order(String customerId, String orderId) {
        return jdbc.sql("""
                        select id, ref, type, state, placed_at, coalesce(subtotal_cents, 0) as subtotal,
                               coalesce(delivery_fee_cents, 0) + coalesce(service_fee_cents, 0) as fees,
                               coalesce(tax_cents, 0) as tax, coalesce(tip_cents, 0) as tip, delivery_kind,
                               discount_cents + points_cents as off,
                               fulfilment_mode, window_id, scheduled_for, delivered_at, delivery_proof, confirmed_at
                          from orders.orders where id = :id and customer_id = :c
                        """)
                .param("id", orderId)
                .param("c", customerId)
                .query((rs, _) -> new Header(
                        rs.getString("id"),
                        rs.getString("ref"),
                        Objects.requireNonNullElse(rs.getString("type"), "goods"),
                        Objects.requireNonNullElse(rs.getString("state"), "placed"),
                        JdbcTimes.requiredInstant(rs, "placed_at"),
                        rs.getLong("subtotal"),
                        rs.getLong("fees"),
                        rs.getLong("tax"),
                        rs.getLong("tip"),
                        rs.getString("delivery_kind"),
                        rs.getString("fulfilment_mode"),
                        rs.getString("window_id"),
                        JdbcTimes.instant(rs, "scheduled_for"),
                        JdbcTimes.instant(rs, "delivered_at"),
                        rs.getString("delivery_proof"),
                        JdbcTimes.instant(rs, "confirmed_at"),
                        rs.getLong("off")))
                .optional();
    }

    @Override
    public boolean exists(String orderId) {
        return jdbc.sql("select exists (select 1 from orders.orders where id = :id)")
                .param("id", orderId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<LineState> lines(String orderId) {
        return jdbc.sql("""
                        select merchant_id, qty, coalesce(state, 'pending') as state from orders.order_lines
                         where order_id = :id order by id
                        """)
                .param("id", orderId)
                .query((rs, _) -> new LineState(rs.getString("merchant_id"), rs.getInt("qty"), rs.getString("state")))
                .list();
    }
}
