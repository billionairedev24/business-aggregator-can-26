package ca.northline.privacy.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.privacy.application.RetentionRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link RetentionRunStore} over {@code privacy.retention_runs} (V295) and {@code privacy.requests}. */
@Repository
@RequiredArgsConstructor
class RetentionRunJdbc implements RetentionRunStore {

    private static final String COLUMNS = """
            id, category, module, dry_run, trigger, actor_id, started_at, finished_at, outcome, affected, held,
            remaining, error""";

    private final JdbcClient jdbc;

    @Override
    public void insert(RunRecord r) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("id", r.id());
        p.put("category", r.category());
        p.put("module", r.module());
        p.put("dry", r.dryRun());
        p.put("trigger", r.trigger());
        p.put("actor", r.actorId());
        p.put("started", ts(r.startedAt()));
        p.put("finished", ts(r.finishedAt()));
        p.put("outcome", r.outcome());
        p.put("affected", r.affected());
        p.put("held", r.held());
        p.put("remaining", r.remaining());
        p.put("error", r.error());
        jdbc.sql("""
                        insert into privacy.retention_runs (%s)
                        values (:id, :category, :module, :dry, :trigger, :actor, :started, :finished, :outcome,
                                :affected, :held, :remaining, :error)
                        """.formatted(COLUMNS))
                .params(p)
                .update();
    }

    @Override
    public Map<String, RunRecord> latest() {
        return byCategory("select distinct on (category) " + COLUMNS
                + " from privacy.retention_runs order by category, started_at desc");
    }

    @Override
    public Map<String, RunRecord> latestSucceeded() {
        return byCategory("select distinct on (category) " + COLUMNS
                + " from privacy.retention_runs where outcome = 'succeeded' and not dry_run"
                + " order by category, finished_at desc");
    }

    private Map<String, RunRecord> byCategory(String sql) {
        return jdbc.sql(sql).query(RetentionRunJdbc::row).list().stream()
                .collect(Collectors.toUnmodifiableMap(RunRecord::category, Function.identity()));
    }

    @Override
    public Optional<Instant> firstRun() {
        return jdbc.sql("select min(started_at) as first from privacy.retention_runs")
                .query((rs, _) -> Optional.ofNullable(instant(rs, "first")))
                .single();
    }

    @Override
    public boolean ranSince(String category, Instant since) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from privacy.retention_runs
                                        where category = :c and trigger = 'schedule' and outcome = 'succeeded'
                                          and finished_at >= :since)
                        """)
                .param("c", category)
                .param("since", ts(since))
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean tryLock(String category) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "privacy.retention:" + category)
                .query(Boolean.class)
                .single());
    }

    @Override
    public Set<String> openRequestSubjects() {
        return Set.copyOf(jdbc.sql("""
                        select distinct subject_id from privacy.requests
                         where state in ('awaiting_verification', 'verified', 'in_progress')
                        """)
                .query((rs, _) -> rs.getString(1))
                .list());
    }

    private static RunRecord row(ResultSet rs, int ignored) throws SQLException {
        return new RunRecord(
                rs.getString("id"),
                rs.getString("category"),
                rs.getString("module"),
                rs.getBoolean("dry_run"),
                rs.getString("trigger"),
                rs.getString("actor_id"),
                requiredInstant(rs, "started_at"),
                requiredInstant(rs, "finished_at"),
                rs.getString("outcome"),
                rs.getLong("affected"),
                rs.getLong("held"),
                rs.getLong("remaining"),
                rs.getString("error"));
    }
}
