package ca.northline.standing.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.standing.application.CoachingStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CoachingStore} over {@code standing.coaching_plans} and {@code coaching_tasks}. */
@Repository
@RequiredArgsConstructor
class CoachingJdbc implements CoachingStore {

    private static final String SELECT = """
            select id, merchant_id, title, note, due_on, state, created_by, created_role, created_at, closed_at,
                   close_reason
              from standing.coaching_plans""";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(Plan p) {
        var inserted = jdbc.sql("""
                        insert into standing.coaching_plans (id, merchant_id, title, note, due_on, state, created_by,
                          created_role, created_at)
                        values (:id, :m, :title, :note, :due, 'active', :by, :role, :at)
                        on conflict do nothing
                        """)
                        .param("id", p.id())
                        .param("m", p.merchantId())
                        .param("title", p.title())
                        .param("note", p.note(), Types.VARCHAR)
                        .param("due", p.dueOn(), Types.DATE)
                        .param("by", p.createdBy())
                        .param("role", p.createdRole())
                        .param("at", ts(p.createdAt()))
                        .update()
                > 0;
        if (!inserted) {
            return false;
        }
        for (var t : p.tasks()) {
            jdbc.sql(
                            "insert into standing.coaching_tasks (id, plan_id, sort, title) values (:id, :plan, :sort, :title)")
                    .param("id", t.id())
                    .param("plan", p.id())
                    .param("sort", t.sort())
                    .param("title", t.title())
                    .update();
        }
        return true;
    }

    @Override
    public Optional<Plan> find(String planId) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", planId)
                .query(this::row)
                .optional();
    }

    @Override
    public Optional<Plan> active(String merchantId) {
        return jdbc.sql(SELECT + " where merchant_id = :m and state = 'active'")
                .param("m", merchantId)
                .query(this::row)
                .optional();
    }

    @Override
    public List<Plan> ofBusiness(String merchantId, int limit) {
        return jdbc.sql(SELECT + " where merchant_id = :m order by created_at desc, id limit :n")
                .param("m", merchantId)
                .param("n", limit)
                .query(this::row)
                .list();
    }

    @Override
    public void close(String planId, String state, String by, @Nullable String reason, Instant at) {
        jdbc.sql("""
                        update standing.coaching_plans
                           set state = :state, closed_by = :by, close_reason = :reason, closed_at = :at
                         where id = :id and state = 'active'
                        """)
                .param("state", state)
                .param("by", by)
                .param("reason", reason, Types.VARCHAR)
                .param("at", ts(at))
                .param("id", planId)
                .update();
    }

    @Override
    public boolean tick(String planId, String taskId, boolean done, String by, Instant at) {
        return jdbc.sql("""
                        update standing.coaching_tasks
                           set done_at = case when :done then coalesce(done_at, :at) end,
                               done_by = case when :done then coalesce(done_by, :by) end
                         where id = :task and plan_id = :plan
                        """)
                        .param("done", done)
                        .param("at", ts(at))
                        .param("by", by)
                        .param("task", taskId)
                        .param("plan", planId)
                        .update()
                > 0;
    }

    private Plan row(ResultSet rs, int i) throws SQLException {
        var id = rs.getString("id");
        return new Plan(
                id,
                rs.getString("merchant_id"),
                rs.getString("title"),
                rs.getString("note"),
                rs.getObject("due_on", LocalDate.class),
                rs.getString("state"),
                rs.getString("created_by"),
                rs.getString("created_role"),
                requiredInstant(rs, "created_at"),
                instant(rs, "closed_at"),
                rs.getString("close_reason"),
                jdbc.sql("""
                                select id, sort, title, done_at, done_by from standing.coaching_tasks
                                 where plan_id = :id order by sort
                                """)
                        .param("id", id)
                        .query((t, _) -> new Task(
                                t.getString("id"),
                                t.getInt("sort"),
                                t.getString("title"),
                                instant(t, "done_at"),
                                t.getString("done_by")))
                        .list());
    }
}
