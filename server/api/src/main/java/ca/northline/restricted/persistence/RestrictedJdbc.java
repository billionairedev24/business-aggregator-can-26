package ca.northline.restricted.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.restricted.api.AgeChecksReport.Refusal;
import ca.northline.restricted.api.HandoffChecks.Check;
import ca.northline.restricted.application.AgeVerificationStore;
import ca.northline.restricted.application.HandoffCheckStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AgeVerificationStore} and {@link HandoffCheckStore} over schema {@code restricted} (V343). */
@Repository
@RequiredArgsConstructor
class RestrictedJdbc implements AgeVerificationStore, HandoffCheckStore {

    private static final String ROW = """
            select user_id, state, over_age, verified_on, method, session_id, last_error, attempts, started_at,
                   updated_at
              from restricted.age_verifications
            """;

    /** Handoff checks in the period, under the province's rules when one is given. */
    private static final String PERIOD = """
             from restricted.handoff_checks
            where checked_at >= :from and checked_at < :to
              and (cast(:province as text) is null or province = cast(:province as text))
            """;

    private final JdbcClient jdbc;

    // ── age verifications ─────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<Row> find(String userId) {
        return jdbc.sql(ROW + " where user_id = :u")
                .param("u", userId)
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public Optional<Row> lockBySession(String sessionId) {
        return jdbc.sql(ROW + " where session_id = :s for update")
                .param("s", sessionId)
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public void save(Row r) {
        jdbc.sql("""
                        insert into restricted.age_verifications
                          (user_id, state, over_age, verified_on, method, session_id, last_error, attempts, started_at,
                           updated_at)
                        values (:u, :state, :over, :on, :method, :session, :error, :attempts, :started, :updated)
                        on conflict (user_id) do update set state = excluded.state, over_age = excluded.over_age,
                          verified_on = excluded.verified_on, method = excluded.method,
                          session_id = excluded.session_id, last_error = excluded.last_error,
                          attempts = excluded.attempts, updated_at = excluded.updated_at
                        """)
                .param("u", r.userId())
                .param("state", r.state())
                .param("over", r.overAge())
                .param("on", r.verifiedOn())
                .param("method", r.method())
                .param("session", r.sessionId())
                .param("error", r.lastError())
                .param("attempts", r.attempts())
                .param("started", ts(r.startedAt()))
                .param("updated", ts(r.updatedAt()))
                .update();
    }

    @Override
    public boolean delete(String userId) {
        return jdbc.sql("delete from restricted.age_verifications where user_id = :u")
                        .param("u", userId)
                        .update()
                > 0;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getString("user_id"),
                rs.getString("state"),
                rs.getObject("over_age", Integer.class),
                rs.getObject("verified_on", LocalDate.class),
                rs.getString("method"),
                rs.getString("session_id"),
                rs.getString("last_error"),
                rs.getInt("attempts"),
                requiredInstant(rs, "started_at"),
                requiredInstant(rs, "updated_at"));
    }

    // ── handoff checks ────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void insert(String id, Check c) {
        jdbc.sql("""
                        insert into restricted.handoff_checks
                          (id, order_id, order_type, merchant_id, province, required_age, actor_id, actor_role, place,
                           outcome, id_checked, recipient_matches, of_age, reason, checked_at)
                        values (:id, :order, :type, :merchant, :province, :age, :actor, :role, :place, :outcome,
                                :idChecked, :matches, :ofAge, :reason, :at)
                        """)
                .param("id", id)
                .param("order", c.orderId())
                .param("type", c.orderType())
                .param("merchant", c.merchantId())
                .param("province", c.province())
                .param("age", c.requiredAge())
                .param("actor", c.actorId())
                .param("role", c.actorRole())
                .param("place", c.place())
                .param("outcome", c.passed() ? "passed" : "refused")
                .param("idChecked", c.idChecked())
                .param("matches", c.recipientMatches())
                .param("ofAge", c.ofAge())
                .param("reason", c.reason())
                .param("at", ts(c.at()))
                .update();
    }

    @Override
    public Map<String, Long> handoffs(Instant from, Instant to, @Nullable String province) {
        return counts("outcome", PERIOD, from, to, province);
    }

    @Override
    public Map<String, Long> refusals(Instant from, Instant to, @Nullable String province) {
        return counts("reason", PERIOD + " and outcome = 'refused'", from, to, province);
    }

    @Override
    public Map<String, Long> byPlace(Instant from, Instant to, @Nullable String province) {
        return counts("place", PERIOD, from, to, province);
    }

    @Override
    public List<Refusal> recentRefusals(Instant from, Instant to, @Nullable String province, int limit) {
        return jdbc.sql("select id, order_id, order_type, province, required_age, actor_role, place, reason, checked_at"
                        + PERIOD
                        + " and outcome = 'refused' order by checked_at desc limit :limit")
                .param("from", ts(from))
                .param("to", ts(to))
                .param("province", province)
                .param("limit", limit)
                .query((rs, _) -> new Refusal(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("order_type"),
                        rs.getString("province"),
                        rs.getInt("required_age"),
                        rs.getString("actor_role"),
                        rs.getString("place"),
                        rs.getString("reason"),
                        requiredInstant(rs, "checked_at")))
                .list();
    }

    @Override
    public Map<String, Long> verifications(Instant from, Instant to) {
        return counts(
                "state",
                " from restricted.age_verifications where updated_at >= :from and updated_at < :to"
                        + " and state in ('verified', 'failed') and cast(:province as text) is null",
                from,
                to,
                null);
    }

    @Override
    public Map<String, Long> failures(Instant from, Instant to) {
        return counts(
                "last_error",
                " from restricted.age_verifications where updated_at >= :from and updated_at < :to"
                        + " and state = 'failed' and cast(:province as text) is null",
                from,
                to,
                null);
    }

    private Map<String, Long> counts(String column, String where, Instant from, Instant to, @Nullable String province) {
        var out = new LinkedHashMap<String, Long>();
        jdbc.sql("select " + column + " as k, count(*) as n " + where + " group by 1 order by 1")
                .param("from", ts(from))
                .param("to", ts(to))
                .param("province", province)
                .query((rs, _) -> Map.entry(String.valueOf(rs.getString("k")), rs.getLong("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
