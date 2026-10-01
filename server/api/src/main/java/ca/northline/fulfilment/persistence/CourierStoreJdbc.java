package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.application.CourierStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CourierStore} on {@code fulfilment.couriers} and {@code fulfilment.shifts}. */
@Repository
@RequiredArgsConstructor
class CourierStoreJdbc implements CourierStore {

    private static final String COURIER = "id, user_id, market, vehicle, status, active, last_assigned_at";
    private static final String SHIFT = "id, courier_id, starts_at, ends_at, state, started_at, ended_at";

    private final JdbcClient jdbc;

    @Override
    public Optional<Courier> byUser(String userId) {
        return jdbc.sql(
                        "select " + COURIER
                                + " from fulfilment.couriers where user_id = :u order by active desc, market nulls last, id limit 1")
                .param("u", userId)
                .query(CourierStoreJdbc::courier)
                .optional();
    }

    @Override
    public Optional<Courier> find(String courierId) {
        return jdbc.sql("select " + COURIER + " from fulfilment.couriers where id = :id")
                .param("id", courierId)
                .query(CourierStoreJdbc::courier)
                .optional();
    }

    @Override
    public boolean insert(Courier c) {
        return jdbc.sql("""
                        insert into fulfilment.couriers (id, user_id, market, vehicle, status, active)
                        values (:id, :user, :market, :vehicle, :status, :active)
                        on conflict (user_id) where market is not null do nothing""")
                        .param("id", c.id())
                        .param("user", c.userId())
                        .param("market", c.market())
                        .param("vehicle", c.vehicle())
                        .param("status", c.status())
                        .param("active", c.active())
                        .update()
                == 1;
    }

    @Override
    public List<Courier> inMarket(@Nullable String market) {
        return jdbc.sql("select " + COURIER + """
                         from fulfilment.couriers
                        where cast(:market as text) is null or lower(market) = lower(cast(:market as text))
                        order by market, id
                        """)
                .param("market", market, Types.VARCHAR)
                .query(CourierStoreJdbc::courier)
                .list();
    }

    @Override
    public List<Courier> available(String market, Instant at) {
        return jdbc.sql("select " + COURIER.replaceAll("(\\w+)", "c.$1") + """
                         from fulfilment.couriers c
                        where c.active and c.status = 'available' and lower(c.market) = lower(:market)
                          and exists (select 1 from fulfilment.shifts s
                                       where s.courier_id = c.id and s.state = 'on' and s.ends_at > :at)
                        order by c.last_assigned_at nulls first, c.id
                        """)
                .param("market", market)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(CourierStoreJdbc::courier)
                .list();
    }

    @Override
    public boolean claim(String courierId, Instant at) {
        return jdbc.sql("""
                        update fulfilment.couriers set status = 'on_run', last_assigned_at = :at
                         where id = :id and status = 'available' and active""")
                        .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("id", courierId)
                        .update()
                == 1;
    }

    @Override
    public void status(String courierId, String status) {
        jdbc.sql("update fulfilment.couriers set status = :s where id = :id")
                .param("s", status)
                .param("id", courierId)
                .update();
    }

    @Override
    public void insertShift(Shift s) {
        jdbc.sql("""
                        insert into fulfilment.shifts (id, courier_id, starts_at, ends_at, state)
                        values (:id, :courier, :starts, :ends, :state)""")
                .param("id", s.id())
                .param("courier", s.courierId())
                .param("starts", JdbcTimes.ts(s.startsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("ends", JdbcTimes.ts(s.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("state", s.state())
                .update();
    }

    @Override
    public Optional<Shift> shift(String shiftId) {
        return jdbc.sql("select " + SHIFT + " from fulfilment.shifts where id = :id")
                .param("id", shiftId)
                .query(CourierStoreJdbc::shift)
                .optional();
    }

    @Override
    public List<Shift> shifts(String courierId, Instant from) {
        return jdbc.sql("select " + SHIFT + """
                         from fulfilment.shifts
                        where courier_id = :c and ends_at > :from and state <> 'cancelled'
                        order by starts_at, id
                        """)
                .param("c", courierId)
                .param("from", JdbcTimes.ts(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(CourierStoreJdbc::shift)
                .list();
    }

    @Override
    public Optional<Shift> onShift(String courierId) {
        return jdbc.sql("select " + SHIFT + " from fulfilment.shifts where courier_id = :c and state = 'on'")
                .param("c", courierId)
                .query(CourierStoreJdbc::shift)
                .optional();
    }

    @Override
    public void startShift(String shiftId, Instant at) {
        jdbc.sql("update fulfilment.shifts set state = 'on', started_at = :at where id = :id and state = 'scheduled'")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", shiftId)
                .update();
    }

    @Override
    public void endShift(String shiftId, Instant at) {
        jdbc.sql("update fulfilment.shifts set state = 'done', ended_at = :at where id = :id and state = 'on'")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", shiftId)
                .update();
    }

    private static Courier courier(ResultSet rs, int rowNum) throws SQLException {
        return new Courier(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("market"),
                rs.getString("vehicle"),
                java.util.Objects.requireNonNullElse(rs.getString("status"), "offline"),
                rs.getBoolean("active"),
                JdbcTimes.instant(rs, "last_assigned_at"));
    }

    private static Shift shift(ResultSet rs, int rowNum) throws SQLException {
        return new Shift(
                rs.getString("id"),
                rs.getString("courier_id"),
                JdbcTimes.requiredInstant(rs, "starts_at"),
                JdbcTimes.requiredInstant(rs, "ends_at"),
                rs.getString("state"),
                JdbcTimes.instant(rs, "started_at"),
                JdbcTimes.instant(rs, "ended_at"));
    }
}
