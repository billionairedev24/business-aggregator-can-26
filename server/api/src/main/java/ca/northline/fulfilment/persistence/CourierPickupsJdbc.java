package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.CourierPickups;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CourierPickups} from {@code fulfilment.stops}, {@code runs} and {@code couriers}. */
@Repository
@RequiredArgsConstructor
class CourierPickupsJdbc implements CourierPickups {

    private static final String SELECT = """
            select distinct on (st.order_id) st.order_id, r.courier_id, cr.user_id, st.eta, st.arrived_at,
                   case when st.state = 'done' then st.done_at end as picked_up_at, r.label
              from fulfilment.stops st
              join fulfilment.runs r on r.id = st.run_id
              left join fulfilment.couriers cr on cr.id = r.courier_id
            """;

    private final JdbcClient jdbc;

    @Override
    public Map<String, Pickup> of(Collection<String> orderIds) {
        var out = new HashMap<String, Pickup>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.sql(SELECT + """
                         where st.order_id in (:ids) and st.kind = 'pickup'
                         order by st.order_id, st.seq nulls last
                        """).param("ids", List.copyOf(orderIds)).query(rs -> {
            out.put(rs.getString("order_id"), pickup(rs));
        });
        return out;
    }

    @Override
    public Map<String, Pickup> atMerchant(String merchantId, Collection<String> orderIds) {
        var out = new HashMap<String, Pickup>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.sql(SELECT + """
                         where st.order_id in (:ids) and st.kind = 'pickup' and st.merchant_id = :m
                         order by st.order_id, st.seq nulls last
                        """)
                .param("ids", List.copyOf(orderIds))
                .param("m", merchantId)
                .query(rs -> {
                    out.put(rs.getString("order_id"), pickup(rs));
                });
        return out;
    }

    private static Pickup pickup(ResultSet rs) throws SQLException {
        return new Pickup(
                rs.getString("courier_id") != null,
                rs.getString("user_id"),
                JdbcTimes.instant(rs, "eta"),
                JdbcTimes.instant(rs, "arrived_at"),
                JdbcTimes.instant(rs, "picked_up_at"),
                rs.getString("label"));
    }
}
