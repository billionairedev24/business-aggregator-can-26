package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.DeliveryAlerts;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link DeliveryAlerts} over {@code fulfilment.deliveries}, {@code runs} and {@code stops} (S-81). */
@Repository
@RequiredArgsConstructor
class DeliveryAlertsJdbc implements DeliveryAlerts {

    private final JdbcClient jdbc;

    @Override
    public Set<String> stuck(Collection<String> orderIds, Instant now, Duration after) {
        if (orderIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                        select d.order_id from fulfilment.deliveries d join fulfilment.runs r on r.id = d.run_id
                         where d.order_id = any(:orders) and r.state <> 'done'
                           and exists (select 1 from fulfilment.stops st
                                        where st.run_id = r.id and st.state = 'pending' and st.eta < :late)
                        """)
                .param("orders", orderIds.toArray(String[]::new))
                .param("late", JdbcTimes.ts(now.minus(after)))
                .query(String.class)
                .list());
    }
}
