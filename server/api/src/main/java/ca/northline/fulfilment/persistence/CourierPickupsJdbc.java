package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.CourierPickups;
import ca.northline.shared.JdbcTimes;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CourierPickups} from {@code fulfilment.stops}, {@code runs} and {@code couriers}: first pickup stop per order. */
@Repository
@RequiredArgsConstructor
class CourierPickupsJdbc implements CourierPickups {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Pickup> of(Collection<String> orderIds) {
        var out = new HashMap<String, Pickup>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select distinct on (st.order_id) st.order_id, r.courier_id, cr.user_id, st.eta, st.arrived_at
                          from fulfilment.stops st
                          join fulfilment.runs r on r.id = st.run_id
                          left join fulfilment.couriers cr on cr.id = r.courier_id
                         where st.order_id in (:ids) and st.kind = 'pickup'
                         order by st.order_id, st.seq nulls last
                        """)
                .param("ids", orderIds)
                .query(rs -> {
                    out.put(
                        rs.getString("order_id"),
                        new Pickup(
                                rs.getString("courier_id") != null,
                                rs.getString("user_id"),
                                JdbcTimes.instant(rs, "eta"),
                                JdbcTimes.instant(rs, "arrived_at")));
                });
        return out;
    }
}
