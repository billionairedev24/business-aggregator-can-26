package ca.northline.orders.persistence;

import ca.northline.orders.application.DispatchFacts;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link DispatchFacts} on {@code orders.orders} and {@code orders.food_checkouts}. */
@Repository
@RequiredArgsConstructor
class DispatchFactsJdbc implements DispatchFacts {

    private final JdbcClient jdbc;

    @Override
    public Optional<Facts> of(String orderId) {
        return jdbc.sql("""
                        select o.id, o.customer_id, o.address_id, o.delivery_area, o.window_id, fc.delivery::text as food,
                               o.id_check_age, coalesce(fc.province, c.province) as province
                          from orders.orders o
                          left join orders.food_checkouts fc on fc.id = o.id
                          left join orders.checkouts c on c.id = o.checkout_id
                         where o.id = :id
                        """)
                .param("id", orderId)
                .query((rs, _) -> new Facts(
                        rs.getString("id"),
                        rs.getString("customer_id"),
                        rs.getString("address_id"),
                        rs.getString("delivery_area"),
                        rs.getString("window_id"),
                        rs.getString("food"),
                        rs.getObject("id_check_age", Integer.class),
                        rs.getString("province") == null
                                ? null
                                : rs.getString("province").strip()))
                .optional();
    }

    @Override
    public boolean pickedUp(String orderId) {
        return jdbc.sql("""
                        update orders.orders set state = 'picked_up'
                         where id = :id and state in ('placed', 'accepted', 'packing', 'ready')""").param("id", orderId).update() == 1;
    }
}
