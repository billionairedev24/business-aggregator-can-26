package ca.northline.food.persistence;

import ca.northline.food.application.KitchenOrderLines;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads order line ids from the orders schema, as the kitchen ticket queries already do (docs/DECISIONS.md). */
@Repository
@RequiredArgsConstructor
class KitchenOrderLinesJdbc implements KitchenOrderLines {

    private final JdbcClient jdbc;

    @Override
    public List<String> lineIds(String merchantId, String orderId) {
        return jdbc.sql(
                        "select id from orders.order_lines where order_id = :orderId and merchant_id = :merchantId order by id")
                .param("orderId", orderId)
                .param("merchantId", merchantId)
                .query((rs, _) -> rs.getString(1))
                .list();
    }
}
