package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import ca.northline.fulfilment.application.ReturnPickupStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link ReturnPickupStore} on {@code fulfilment.return_pickups} (V433). */
@Repository
@RequiredArgsConstructor
class ReturnPickupJdbc implements ReturnPickupStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = """
            return_id, return_ref, order_id, merchant_id, market, customer_id, collect_from::text as collect_from, state,
            run_id, created_at""";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(ReturnPickup p) {
        return jdbc.sql("""
                                insert into fulfilment.return_pickups (return_id, return_ref, order_id, merchant_id,
                                       market, customer_id, collect_from, state, created_at, updated_at)
                                values (:id, :ref, :order, :merchant, :market, :customer, cast(:from as jsonb),
                                        'waiting', :at, :at)
                                on conflict (return_id) do nothing
                                """)
                        .param("id", p.returnId())
                        .param("ref", p.returnRef(), Types.VARCHAR)
                        .param("order", p.orderId())
                        .param("merchant", p.merchantId())
                        .param("market", p.market())
                        .param("customer", p.customerId(), Types.VARCHAR)
                        .param(
                                "from",
                                p.collectFrom() == null ? null : JSON.writeValueAsString(p.collectFrom()),
                                Types.VARCHAR)
                        .param("at", JdbcTimes.ts(p.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update()
                == 1;
    }

    @Override
    public Optional<ReturnPickup> find(String returnId) {
        return jdbc.sql("select " + COLUMNS + " from fulfilment.return_pickups where return_id = :id")
                .param("id", returnId)
                .query(ReturnPickupJdbc::row)
                .optional();
    }

    @Override
    public List<ReturnPickup> waiting() {
        return jdbc.sql("select " + COLUMNS
                        + " from fulfilment.return_pickups where state = 'waiting' order by created_at, return_id")
                .query(ReturnPickupJdbc::row)
                .list();
    }

    @Override
    public List<ReturnPickup> onRun(String runId) {
        return jdbc.sql("select " + COLUMNS + " from fulfilment.return_pickups where run_id = :r order by return_id")
                .param("r", runId)
                .query(ReturnPickupJdbc::row)
                .list();
    }

    @Override
    public void attach(String returnId, String runId, Instant at) {
        jdbc.sql("""
                        update fulfilment.return_pickups set run_id = :r, state = 'planned', updated_at = :at
                         where return_id = :id""")
                .param("id", returnId)
                .param("r", runId)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public void moveState(String returnId, String state, Instant at) {
        jdbc.sql("update fulfilment.return_pickups set state = :s, updated_at = :at where return_id = :id")
                .param("id", returnId)
                .param("s", state)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public int forgetAddresses(Instant before) {
        return jdbc.sql("""
                        update fulfilment.return_pickups set collect_from = null
                         where collect_from is not null and state in ('returned', 'cancelled') and updated_at < :before""")
                .param("before", JdbcTimes.ts(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static ReturnPickup row(ResultSet rs, int rowNum) throws SQLException {
        var from = rs.getString("collect_from");
        return new ReturnPickup(
                rs.getString("return_id"),
                rs.getString("return_ref"),
                rs.getString("order_id"),
                rs.getString("merchant_id"),
                rs.getString("market"),
                rs.getString("customer_id"),
                from == null ? null : JSON.readValue(from, Dropoff.class),
                rs.getString("state"),
                rs.getString("run_id"),
                JdbcTimes.requiredInstant(rs, "created_at"));
    }
}
