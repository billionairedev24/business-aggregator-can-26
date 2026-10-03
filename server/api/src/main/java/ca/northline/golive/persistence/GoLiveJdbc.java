package ca.northline.golive.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.golive.application.GoLiveStore;
import ca.northline.golive.domain.GateStatus;
import ca.northline.golive.domain.LaunchRequest;
import ca.northline.shared.CodedEnum;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link GoLiveStore} over schema {@code golive} (V330). */
@Repository
@RequiredArgsConstructor
class GoLiveJdbc implements GoLiveStore {

    private static final String REQUEST_COLUMNS = """
            id, market_id, state, requested_by, requested_at, expires_at, note, override, override_reason, blocking,
            decided_by, decided_at, decision_note""";

    private final JdbcClient jdbc;

    @Override
    public Map<String, GateRecord> latestRecords(String marketId) {
        return jdbc
                .sql("""
                        select distinct on (gate) id, market_id, gate, status, evidence, evidence_url, source, recorded_by,
                               recorded_at
                          from golive.gate_records where market_id = :m
                         order by gate, recorded_at desc, id desc
                        """)
                .param("m", marketId)
                .query((rs, _) -> new GateRecord(
                        rs.getString("id"),
                        rs.getString("market_id"),
                        rs.getString("gate"),
                        CodedEnum.fromCode(GateStatus.class, rs.getString("status")),
                        rs.getString("evidence"),
                        rs.getString("evidence_url"),
                        rs.getString("source"),
                        rs.getString("recorded_by"),
                        requiredInstant(rs, "recorded_at")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(GateRecord::gate, Function.identity()));
    }

    @Override
    public void insertRecord(GateRecord r) {
        jdbc.sql("""
                        insert into golive.gate_records
                          (id, market_id, gate, status, evidence, evidence_url, source, recorded_by, recorded_at)
                        values (:id, :m, :gate, :status, :evidence, :url, :source, :by, :at)
                        """)
                .param("id", r.id())
                .param("m", r.marketId())
                .param("gate", r.gate())
                .param("status", r.status().code())
                .param("evidence", r.evidence())
                .param("url", r.evidenceUrl())
                .param("source", r.source())
                .param("by", r.recordedBy())
                .param("at", ts(r.recordedAt()))
                .update();
    }

    @Override
    public Optional<LaunchRequest> lockOpenRequest(String marketId) {
        return jdbc.sql("select " + REQUEST_COLUMNS
                        + " from golive.launch_requests where market_id = :m and state = 'pending' for update")
                .param("m", marketId)
                .query(GoLiveJdbc::request)
                .optional();
    }

    @Override
    public Optional<LaunchRequest> lockRequest(String requestId) {
        return jdbc.sql("select " + REQUEST_COLUMNS + " from golive.launch_requests where id = :id for update")
                .param("id", requestId)
                .query(GoLiveJdbc::request)
                .optional();
    }

    @Override
    public Map<String, LaunchRequest> openRequests() {
        return jdbc
                .sql("select " + REQUEST_COLUMNS + " from golive.launch_requests where state = 'pending'")
                .query(GoLiveJdbc::request)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(LaunchRequest::marketId, Function.identity()));
    }

    @Override
    public void insertRequest(LaunchRequest r) {
        jdbc.sql("""
                        insert into golive.launch_requests
                          (id, market_id, state, requested_by, requested_at, expires_at, note, override, override_reason,
                           blocking)
                        values (:id, :m, :state, :by, :at, :expires, :note, :override, :reason, :blocking)
                        """)
                .param("id", r.id())
                .param("m", r.marketId())
                .param("state", r.state().code())
                .param("by", r.requestedBy())
                .param("at", ts(r.requestedAt()))
                .param("expires", ts(r.expiresAt()))
                .param("note", r.note())
                .param("override", r.override())
                .param("reason", r.overrideReason())
                .param("blocking", r.blocking().toArray(String[]::new))
                .update();
    }

    @Override
    public void updateRequest(LaunchRequest r) {
        jdbc.sql("""
                        update golive.launch_requests
                           set state = :state, decided_by = :by, decided_at = :at, decision_note = :note
                         where id = :id
                        """)
                .param("id", r.id())
                .param("state", r.state().code())
                .param("by", r.decidedBy())
                .param("at", ts(r.decidedAt()))
                .param("note", r.decisionNote())
                .update();
    }

    @Override
    public List<LaunchRequest> requests(String marketId, int limit) {
        return jdbc.sql(
                        "select " + REQUEST_COLUMNS
                                + " from golive.launch_requests where market_id = :m order by requested_at desc, id desc limit :n")
                .param("m", marketId)
                .param("n", limit)
                .query(GoLiveJdbc::request)
                .list();
    }

    @Override
    public void insertEvent(MarketEvent e) {
        jdbc.sql("""
                        insert into golive.market_events (id, market_id, kind, request_id, actor_id, reason, occurred_at)
                        values (:id, :m, :kind, :request, :actor, :reason, :at)
                        """)
                .param("id", e.id())
                .param("m", e.marketId())
                .param("kind", e.kind())
                .param("request", e.requestId())
                .param("actor", e.actorId())
                .param("reason", e.reason())
                .param("at", ts(e.occurredAt()))
                .update();
    }

    @Override
    public List<MarketEvent> events(String marketId, int limit) {
        return jdbc.sql("""
                        select id, market_id, kind, request_id, actor_id, reason, occurred_at from golive.market_events
                         where market_id = :m order by occurred_at desc, id desc limit :n
                        """)
                .param("m", marketId)
                .param("n", limit)
                .query((rs, _) -> new MarketEvent(
                        rs.getString("id"),
                        rs.getString("market_id"),
                        rs.getString("kind"),
                        rs.getString("request_id"),
                        rs.getString("actor_id"),
                        rs.getString("reason"),
                        requiredInstant(rs, "occurred_at")))
                .list();
    }

    @Override
    public List<HypercareDay> hypercare(String marketId) {
        return jdbc.sql("""
                        select market_id, day, primary_user_id, secondary_user_id, business_user_id, primary_shift_id,
                               secondary_shift_id, created_by, created_at
                          from golive.hypercare_days where market_id = :m order by day
                        """)
                .param("m", marketId)
                .query((rs, _) -> new HypercareDay(
                        rs.getString("market_id"),
                        rs.getObject("day", java.time.LocalDate.class),
                        rs.getString("primary_user_id"),
                        rs.getString("secondary_user_id"),
                        rs.getString("business_user_id"),
                        rs.getString("primary_shift_id"),
                        rs.getString("secondary_shift_id"),
                        rs.getString("created_by"),
                        requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public void insertHypercare(List<HypercareDay> days) {
        for (var d : days) {
            jdbc.sql("""
                            insert into golive.hypercare_days
                              (market_id, day, primary_user_id, secondary_user_id, business_user_id, primary_shift_id,
                               secondary_shift_id, created_by, created_at)
                            values (:m, :day, :p, :s, :b, :ps, :ss, :by, :at)
                            """)
                    .param("m", d.marketId())
                    .param("day", d.day())
                    .param("p", d.primary())
                    .param("s", d.secondary())
                    .param("b", d.business())
                    .param("ps", d.primaryShiftId())
                    .param("ss", d.secondaryShiftId())
                    .param("by", d.createdBy())
                    .param("at", ts(d.createdAt()))
                    .update();
        }
    }

    private static LaunchRequest request(ResultSet rs, int ignored) throws SQLException {
        return new LaunchRequest(
                rs.getString("id"),
                rs.getString("market_id"),
                CodedEnum.fromCode(LaunchRequest.State.class, rs.getString("state")),
                rs.getString("requested_by"),
                requiredInstant(rs, "requested_at"),
                requiredInstant(rs, "expires_at"),
                rs.getString("note"),
                rs.getBoolean("override"),
                rs.getString("override_reason"),
                strings(rs.getArray("blocking")),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("decision_note"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
