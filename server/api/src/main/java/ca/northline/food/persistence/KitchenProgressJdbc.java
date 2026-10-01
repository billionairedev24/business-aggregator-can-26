package ca.northline.food.persistence;

import ca.northline.food.api.KitchenProgress;
import ca.northline.shared.JdbcTimes;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link KitchenProgress} from {@code food.kitchen_tickets} (no row = the kitchen hasn't touched the order yet). */
@Repository
@RequiredArgsConstructor
class KitchenProgressJdbc implements KitchenProgress {

    private final JdbcClient jdbc;

    @Override
    public Optional<Ticket> of(String orderId) {
        return jdbc.sql("""
                        select stage, prep_min, accepted_at, ready_by, ready_at, handed_off_at
                          from food.kitchen_tickets where order_id = :id
                        """)
                .param("id", orderId)
                .query((rs, _) -> new Ticket(
                        rs.getString("stage"),
                        KitchenSql.intOrNull(rs, "prep_min"),
                        JdbcTimes.instant(rs, "accepted_at"),
                        JdbcTimes.instant(rs, "ready_by"),
                        JdbcTimes.instant(rs, "ready_at"),
                        JdbcTimes.instant(rs, "handed_off_at")))
                .optional();
    }
}
