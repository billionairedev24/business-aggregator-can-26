package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import ca.northline.fulfilment.application.DeliveryStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link DeliveryStore} on {@code fulfilment.deliveries} and {@code delivery_pickups}. */
@Repository
@RequiredArgsConstructor
class DeliveryStoreJdbc implements DeliveryStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = """
            order_id, order_ref, order_type, kind, market, window_id, window_label, starts_at, ends_at, order_by,
            pack_by, ready_by, customer_id, dropoff::text as dropoff, pin, state, run_id""";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(Delivery d) {
        return jdbc.sql("""
                        insert into fulfilment.deliveries (order_id, order_ref, order_type, kind, market, window_id,
                               window_label, starts_at, ends_at, order_by, pack_by, ready_by, customer_id, dropoff, pin,
                               state)
                        values (:id, :ref, :type, :kind, :market, :window, :label, :starts, :ends, :orderBy, :packBy,
                                :readyBy, :customer, cast(:dropoff as jsonb), :pin, 'waiting')
                        on conflict (order_id) do nothing
                        """)
                        .param("id", d.orderId())
                        .param("ref", d.orderRef())
                        .param("type", d.orderType())
                        .param("kind", d.kind())
                        .param("market", d.market())
                        .param("window", d.windowId())
                        .param("label", d.windowLabel())
                        .param("starts", JdbcTimes.ts(d.startsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("ends", JdbcTimes.ts(d.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("orderBy", JdbcTimes.ts(d.orderBy()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("packBy", JdbcTimes.ts(d.packBy()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("readyBy", JdbcTimes.ts(d.readyBy()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("customer", d.customerId())
                        .param(
                                "dropoff",
                                d.dropoff() == null ? null : JSON.writeValueAsString(d.dropoff()),
                                Types.VARCHAR)
                        .param("pin", d.pin())
                        .update()
                == 1;
    }

    @Override
    public void addPickup(String orderId, String merchantId) {
        jdbc.sql("""
                        insert into fulfilment.delivery_pickups (order_id, merchant_id) values (:o, :m)
                        on conflict do nothing""").param("o", orderId).param("m", merchantId).update();
    }

    @Override
    public void packed(String orderId, String merchantId, Instant at) {
        jdbc.sql("""
                        update fulfilment.delivery_pickups set packed_at = coalesce(packed_at, :at)
                         where order_id = :o and merchant_id = :m""")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("o", orderId)
                .param("m", merchantId)
                .update();
    }

    @Override
    public void readyBy(String orderId, Instant readyBy) {
        jdbc.sql("update fulfilment.deliveries set ready_by = :at, updated_at = now() where order_id = :o")
                .param("at", JdbcTimes.ts(readyBy), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("o", orderId)
                .update();
    }

    @Override
    public List<Delivery> waitingPooled(Instant now) {
        return list(jdbc.sql("select " + COLUMNS + """
                         from fulfilment.deliveries
                        where state = 'waiting' and kind = 'pooled' and order_by <= :now and ends_at > :now
                        order by window_id, order_id
                        """).param("now", JdbcTimes.ts(now), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Override
    public List<Delivery> waitingDirect() {
        return list(jdbc.sql("select " + COLUMNS + """
                 from fulfilment.deliveries
                where state = 'waiting' and kind = 'direct'
                order by created_at, order_id
                """));
    }

    @Override
    public Optional<Delivery> find(String orderId) {
        return list(jdbc.sql("select " + COLUMNS + " from fulfilment.deliveries where order_id = :o")
                        .param("o", orderId))
                .stream()
                .findFirst();
    }

    @Override
    public List<Delivery> onRun(String runId) {
        return list(jdbc.sql("select " + COLUMNS + " from fulfilment.deliveries where run_id = :r order by order_id")
                .param("r", runId));
    }

    @Override
    public void attach(Collection<String> orderIds, String runId, Instant at) {
        if (orderIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update fulfilment.deliveries set run_id = :r, state = 'planned', updated_at = :at
                         where order_id in (:ids)""")
                .param("r", runId)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("ids", List.copyOf(orderIds))
                .update();
    }

    @Override
    public void moveState(String orderId, String state, Instant at) {
        jdbc.sql("update fulfilment.deliveries set state = :s, updated_at = :at where order_id = :o")
                .param("s", state)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("o", orderId)
                .update();
    }

    @Override
    public void idCheck(String orderId, int age, @Nullable String province) {
        jdbc.sql("update fulfilment.deliveries set id_check_age = :a, id_check_province = :p where order_id = :o")
                .param("a", age)
                .param("p", province, Types.VARCHAR)
                .param("o", orderId)
                .update();
    }

    @Override
    public Optional<IdCheck> idCheck(String orderId) {
        return jdbc.sql("""
                        select id_check_age, id_check_province from fulfilment.deliveries
                         where order_id = :o and id_check_age is not null""")
                .param("o", orderId)
                .query((rs, _) -> new IdCheck(rs.getInt("id_check_age"), rs.getString("id_check_province")))
                .optional();
    }

    @Override
    public int forgetAddresses(Instant before) {
        return jdbc.sql("""
                        update fulfilment.deliveries set dropoff = null
                         where dropoff is not null and state in ('delivered', 'cancelled', 'returned')
                           and updated_at < :before""")
                .param("before", JdbcTimes.ts(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /** The rows plus their pickups (one more query for all of them). */
    private List<Delivery> list(JdbcClient.StatementSpec spec) {
        var rows = spec.query(DeliveryStoreJdbc::row).list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<Pickup>> pickups = new HashMap<>();
        jdbc.sql("""
                        select order_id, merchant_id, packed_at from fulfilment.delivery_pickups
                         where order_id in (:ids) order by merchant_id""")
                .param("ids", rows.stream().map(Delivery::orderId).toList())
                .query(rs -> {
                    pickups.computeIfAbsent(rs.getString("order_id"), _ -> new ArrayList<>())
                            .add(new Pickup(rs.getString("merchant_id"), JdbcTimes.instant(rs, "packed_at")));
                });
        return rows.stream()
                .map(d -> new Delivery(
                        d.orderId(),
                        d.orderRef(),
                        d.orderType(),
                        d.kind(),
                        d.market(),
                        d.windowId(),
                        d.windowLabel(),
                        d.startsAt(),
                        d.endsAt(),
                        d.orderBy(),
                        d.packBy(),
                        d.readyBy(),
                        d.customerId(),
                        d.dropoff(),
                        d.pin(),
                        d.state(),
                        d.runId(),
                        pickups.getOrDefault(d.orderId(), List.of())))
                .toList();
    }

    private static Delivery row(ResultSet rs, int rowNum) throws SQLException {
        return new Delivery(
                rs.getString("order_id"),
                rs.getString("order_ref"),
                rs.getString("order_type"),
                rs.getString("kind"),
                rs.getString("market"),
                rs.getString("window_id"),
                rs.getString("window_label"),
                JdbcTimes.instant(rs, "starts_at"),
                JdbcTimes.instant(rs, "ends_at"),
                JdbcTimes.instant(rs, "order_by"),
                JdbcTimes.instant(rs, "pack_by"),
                JdbcTimes.instant(rs, "ready_by"),
                rs.getString("customer_id"),
                dropoff(rs.getString("dropoff")),
                rs.getString("pin"),
                rs.getString("state"),
                rs.getString("run_id"),
                List.of());
    }

    private static @Nullable Dropoff dropoff(@Nullable String json) {
        return json == null ? null : JSON.readValue(json, Dropoff.class);
    }
}
