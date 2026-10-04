package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.application.RunStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link RunStore} on {@code fulfilment.runs} and {@code fulfilment.stops}. */
@Repository
@RequiredArgsConstructor
class RunStoreJdbc implements RunStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** {@code pg_advisory_xact_lock} key of the planner ("fulfilment.plan"). */
    private static final long PLANNING_LOCK = 0x6e6c_706c_616eL;

    private static final String RUN = """
            id, market, kind, state, window_id, label, part, starts_at, ends_at, pack_by, courier_id, planned_at,
            assigned_at, started_at, done_at, heuristic""";
    private static final String STOP = """
            id, run_id, order_id, kind, seq, merchant_id, eta, state, arrived_at, done_at, proof_kind, proof_media_id,
            scan_ok""";

    private final JdbcClient jdbc;

    @Override
    public void lockPlanning() {
        jdbc.sql("select pg_advisory_xact_lock(:k)")
                .param("k", PLANNING_LOCK)
                .query()
                .singleRow();
    }

    @Override
    public int nextPart(String windowId) {
        return jdbc.sql("select coalesce(max(part), 0) + 1 from fulfilment.runs where window_id = :w")
                .param("w", windowId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void insert(Run run, List<Stop> stops) {
        var route = JSON.writeValueAsString(stops.stream()
                .map(s -> new RoutePoint(s.id(), s.seq(), s.kind(), s.orderId(), s.merchantId(), s.eta()))
                .toList());
        jdbc.sql("""
                        insert into fulfilment.runs (id, market, kind, state, window_id, label, part, starts_at, ends_at,
                               pack_by, planned_at, heuristic, route)
                        values (:id, :market, :kind, 'planned', :window, :label, :part, :starts, :ends, :packBy, :at,
                                :heuristic, cast(:route as jsonb))
                        """)
                .param("id", run.id())
                .param("market", run.market())
                .param("kind", run.kind())
                .param("window", run.windowId())
                .param("label", run.label())
                .param("part", run.part())
                .param("starts", JdbcTimes.ts(run.startsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("ends", JdbcTimes.ts(run.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("packBy", JdbcTimes.ts(run.packBy()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("at", JdbcTimes.ts(run.plannedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("heuristic", run.heuristic())
                .param("route", route)
                .update();
        for (var s : stops) {
            jdbc.sql("""
                            insert into fulfilment.stops (id, run_id, order_id, kind, seq, merchant_id, eta, state)
                            values (:id, :run, :order, :kind, :seq, :merchant, :eta, 'pending')
                            """)
                    .param("id", s.id())
                    .param("run", run.id())
                    .param("order", s.orderId())
                    .param("kind", s.kind())
                    .param("seq", s.seq())
                    .param("merchant", s.merchantId())
                    .param("eta", JdbcTimes.ts(s.eta()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
        }
    }

    /** One entry of {@code runs.route}: the ordered stops and ETAs (V010's "ordered stops + ETAs"). */
    private record RoutePoint(
            String stopId,
            int seq,
            String kind,
            String orderId,
            @Nullable String merchantId,
            @Nullable Instant eta) {}

    @Override
    public List<Run> awaitingCourier(Instant until) {
        return jdbc.sql("select " + RUN + """
                         from fulfilment.runs
                        where state = 'planned' and courier_id is null and (kind = 'direct' or starts_at <= :until)
                        order by starts_at, id
                        """)
                .param("until", JdbcTimes.ts(until), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(RunStoreJdbc::run)
                .list();
    }

    @Override
    public Optional<Run> lockUnassigned(String runId) {
        return jdbc.sql("select " + RUN + """
                         from fulfilment.runs
                        where id = :id and courier_id is null and state = 'planned'
                        for update skip locked
                        """)
                .param("id", runId)
                .query(RunStoreJdbc::run)
                .optional();
    }

    @Override
    public Optional<Run> lock(String runId) {
        return jdbc.sql("select " + RUN + " from fulfilment.runs where id = :id for update")
                .param("id", runId)
                .query(RunStoreJdbc::run)
                .optional();
    }

    @Override
    public Optional<Run> find(String runId) {
        return jdbc.sql("select " + RUN + " from fulfilment.runs where id = :id")
                .param("id", runId)
                .query(RunStoreJdbc::run)
                .optional();
    }

    @Override
    public Optional<Run> openRunOf(String courierId) {
        return jdbc.sql("select " + RUN + " from fulfilment.runs where courier_id = :c and state <> 'done'")
                .param("c", courierId)
                .query(RunStoreJdbc::run)
                .optional();
    }

    @Override
    public void assign(String runId, String courierId, Instant at) {
        jdbc.sql("update fulfilment.runs set courier_id = :c, assigned_at = :at where id = :id")
                .param("c", courierId)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", runId)
                .update();
    }

    @Override
    public void unassign(String runId) {
        jdbc.sql("update fulfilment.runs set courier_id = null, assigned_at = null where id = :id")
                .param("id", runId)
                .update();
    }

    @Override
    public void moveState(String runId, String state, Instant at) {
        jdbc.sql("""
                        update fulfilment.runs set state = :s,
                               started_at = case when :s in ('loading', 'en_route') then coalesce(started_at, :at)
                                                 else started_at end,
                               done_at = case when :s = 'done' then :at else done_at end
                         where id = :id""")
                .param("s", state)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", runId)
                .update();
    }

    @Override
    public List<Stop> stops(String runId) {
        return jdbc.sql("select " + STOP + " from fulfilment.stops where run_id = :r order by seq, id")
                .param("r", runId)
                .query(RunStoreJdbc::stop)
                .list();
    }

    @Override
    public List<Stop> stops(Collection<String> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + STOP + " from fulfilment.stops where run_id in (:r) order by run_id, seq, id")
                .param("r", List.copyOf(runIds))
                .query(RunStoreJdbc::stop)
                .list();
    }

    @Override
    public Optional<Stop> stop(String stopId) {
        return jdbc.sql("select " + STOP + " from fulfilment.stops where id = :id")
                .param("id", stopId)
                .query(RunStoreJdbc::stop)
                .optional();
    }

    @Override
    public void arrived(String stopId, Instant at) {
        jdbc.sql("""
                        update fulfilment.stops set arrived_at = coalesce(arrived_at, :at),
                               state = case when state = 'pending' then 'arrived' else state end
                         where id = :id""")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", stopId)
                .update();
    }

    @Override
    public void pickedUp(String stopId, boolean scanOk, Instant at) {
        jdbc.sql("""
                        update fulfilment.stops set state = 'done', done_at = :at, scan_ok = :scan,
                               arrived_at = coalesce(arrived_at, :at)
                         where id = :id""")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("scan", scanOk)
                .param("id", stopId)
                .update();
    }

    @Override
    public void proofStored(String stopId, String kind, String key) {
        jdbc.sql("update fulfilment.stops set proof_kind = :k, proof_media_id = :key where id = :id")
                .param("k", kind)
                .param("key", key)
                .param("id", stopId)
                .update();
    }

    @Override
    public Optional<String> proofPhotoKey(String orderId) {
        return jdbc.sql("""
                        select s.proof_media_id from fulfilment.stops s
                         where s.order_id = :o and s.kind = 'dropoff' and s.state = 'done' and s.proof_kind = 'photo'
                           and s.proof_media_id is not null
                           -- 2026-10-04: a handoff with an ID check never shows a photo (it could show the ID)
                           and not exists (select 1 from fulfilment.deliveries d
                                            where d.order_id = s.order_id and d.id_check_age is not null)
                         order by s.done_at desc limit 1""").param("o", orderId).query(String.class).optional();
    }

    @Override
    public void droppedOff(String stopId, String proofKind, Instant at) {
        jdbc.sql("""
                        update fulfilment.stops set state = 'done', done_at = :at, proof_kind = :k,
                               arrived_at = coalesce(arrived_at, :at)
                         where id = :id""")
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("k", proofKind)
                .param("id", stopId)
                .update();
    }

    @Override
    public String addReturnStop(String runId, String orderId, String merchantId, Instant at) {
        var id = ca.northline.shared.Ids.next();
        jdbc.sql("""
                        insert into fulfilment.stops (id, run_id, order_id, kind, seq, merchant_id, eta, state)
                        values (:id, :run, :order, 'return',
                                (select coalesce(max(seq), 0) + 1 from fulfilment.stops where run_id = :run),
                                :merchant, :at, 'pending')
                        """)
                .param("id", id)
                .param("run", runId)
                .param("order", orderId)
                .param("merchant", merchantId)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return id;
    }

    @Override
    public List<Run> runs(@Nullable String market, Instant from, Instant to) {
        return jdbc.sql("select " + RUN + """
                         from fulfilment.runs
                        where (cast(:market as text) is null or lower(market) = lower(cast(:market as text)))
                          and starts_at >= :from and starts_at < :to
                        order by starts_at, label, part, id
                        """)
                .param("market", market, Types.VARCHAR)
                .param("from", JdbcTimes.ts(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", JdbcTimes.ts(to), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(RunStoreJdbc::run)
                .list();
    }

    private static Run run(ResultSet rs, int rowNum) throws SQLException {
        return new Run(
                rs.getString("id"),
                rs.getString("market"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getString("window_id"),
                rs.getString("label"),
                rs.getInt("part"),
                JdbcTimes.instant(rs, "starts_at"),
                JdbcTimes.instant(rs, "ends_at"),
                JdbcTimes.instant(rs, "pack_by"),
                rs.getString("courier_id"),
                JdbcTimes.instant(rs, "planned_at"),
                JdbcTimes.instant(rs, "assigned_at"),
                JdbcTimes.instant(rs, "started_at"),
                JdbcTimes.instant(rs, "done_at"),
                java.util.Objects.requireNonNullElse(rs.getString("heuristic"), ""));
    }

    private static Stop stop(ResultSet rs, int rowNum) throws SQLException {
        var scan = rs.getBoolean("scan_ok");
        Boolean scanOk = rs.wasNull() ? null : scan;
        return new Stop(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("order_id"),
                rs.getString("kind"),
                rs.getInt("seq"),
                rs.getString("merchant_id"),
                JdbcTimes.instant(rs, "eta"),
                rs.getString("state"),
                JdbcTimes.instant(rs, "arrived_at"),
                JdbcTimes.instant(rs, "done_at"),
                rs.getString("proof_kind"),
                rs.getString("proof_media_id"),
                scanOk);
    }
}
