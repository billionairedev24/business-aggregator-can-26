package ca.northline.orders.persistence;

import ca.northline.orders.application.FoodOrderStates;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Food-order state moves driven by the kitchen display (kitchen workstream). */
@Repository
@RequiredArgsConstructor
class FoodOrderStatesJdbc implements FoodOrderStates {

    private final JdbcClient jdbc;

    @Override
    public void move(String orderId, Set<String> from, String state, @Nullable Instant deliveredAt) {
        jdbc.sql("""
                        update orders.orders set state = :state, delivered_at = coalesce(:deliveredAt, delivered_at)
                         where id = :id and type = 'food' and state in (:from)
                        """)
                .param("state", state)
                .param("deliveredAt", JdbcTimes.ts(deliveredAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", orderId)
                .param("from", from)
                .update();
    }
}
