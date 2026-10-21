package ca.northline.standing.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.MerchantScope;
import ca.northline.standing.application.AppealStore;
import ca.northline.standing.domain.Appeal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AppealStore} over {@code standing.appeals} and {@code appeal_files}. */
@Repository
@RequiredArgsConstructor
class AppealJdbc implements AppealStore {

    private static final String SELECT = """
            select id, number, merchant_id, subject_type, subject_id, subject_kind, original_actor, statement, state,
                   filed_by, filed_at, assignee_id, assigned_at, decision_reason, decided_by, decided_role,
                   decided_at, compensation_cents
              from standing.appeals""";

    private final JdbcClient jdbc;

    @Override
    public String nextNumber() {
        return "AP-"
                + jdbc.sql("select nextval('standing.appeal_number_seq')")
                        .query(Long.class)
                        .single();
    }

    @Override
    public boolean insert(Appeal a) {
        return jdbc.sql("""
                        insert into standing.appeals (id, number, merchant_id, subject_type, subject_id, subject_kind,
                          original_actor, statement, state, filed_by, filed_at)
                        values (:id, :number, :merchant, :type, :subject, :kind, :original, :statement, :state, :by, :at)
                        on conflict (subject_type, subject_id) do nothing
                        """)
                        .param("id", a.id())
                        .param("number", a.number())
                        .param("merchant", a.merchantId())
                        .param("type", a.subjectType().code())
                        .param("subject", a.subjectId())
                        .param("kind", a.subjectKind())
                        .param("original", a.originalActor(), Types.VARCHAR)
                        .param("statement", a.statement())
                        .param("state", a.state().code())
                        .param("by", a.filedBy())
                        .param("at", ts(a.filedAt()))
                        .update()
                > 0;
    }

    @Override
    public Optional<Appeal> find(String id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(this::row)
                .optional();
    }

    @Override
    public Optional<Appeal> lock(String id) {
        return jdbc.sql(SELECT + " where id = :id for update")
                .param("id", id)
                .query(this::row)
                .optional();
    }

    @Override
    public void update(Appeal a) {
        jdbc.sql("""
                        update standing.appeals
                           set state = :state, assignee_id = :assignee, assigned_at = :assignedAt,
                               decision_reason = :reason, decided_by = :decidedBy, decided_role = :role,
                               decided_at = :decidedAt, compensation_cents = :cents
                         where id = :id
                        """)
                .param("state", a.state().code())
                .param("assignee", a.assigneeId(), Types.VARCHAR)
                .param("assignedAt", ts(a.assignedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("reason", a.decisionReason(), Types.VARCHAR)
                .param("decidedBy", a.decidedBy(), Types.VARCHAR)
                .param("role", a.decidedRole(), Types.VARCHAR)
                .param("decidedAt", ts(a.decidedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("cents", a.compensationCents())
                .param("id", a.id())
                .update();
    }

    @Override
    public void insertFile(String appealId, Appeal.AppealFile f) {
        jdbc.sql("""
                        insert into standing.appeal_files (id, appeal_id, name, content_type, size_bytes, storage_key,
                          created_at) values (:id, :appeal, :name, :type, :size, :key, :at)
                        """)
                .param("id", f.id())
                .param("appeal", appealId)
                .param("name", f.name())
                .param("type", f.contentType())
                .param("size", f.size())
                .param("key", f.storageKey())
                .param("at", ts(f.createdAt()))
                .update();
    }

    @Override
    public Optional<Appeal> forSubject(Appeal.Subject subject, String subjectId) {
        return jdbc.sql(SELECT + " where subject_type = :type and subject_id = :id")
                .param("type", subject.code())
                .param("id", subjectId)
                .query(this::row)
                .optional();
    }

    @Override
    public List<Appeal> ofBusiness(String merchantId, int limit) {
        return jdbc.sql(SELECT + " where merchant_id = :m order by filed_at desc, id limit :n")
                .param("m", merchantId)
                .param("n", limit)
                .query(this::row)
                .list();
    }

    @Override
    public List<Appeal> queue(MerchantScope scope, Instant decidedSince, int limit) {
        return jdbc.sql(SELECT + """
                         where (:everyone or merchant_id = any(:merchants))
                           and (state in ('open', 'in_review') or decided_at >= :since)
                         order by (state in ('open', 'in_review')) desc, filed_at, id
                         limit :n
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(decidedSince))
                .param("n", limit)
                .query(this::row)
                .list();
    }

    private Appeal row(ResultSet rs, int i) throws SQLException {
        var id = rs.getString("id");
        return new Appeal(
                id,
                rs.getString("number"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(Appeal.Subject.class, Objects.requireNonNull(rs.getString("subject_type"))),
                rs.getString("subject_id"),
                rs.getString("subject_kind"),
                rs.getString("original_actor"),
                rs.getString("statement"),
                CodedEnum.fromCode(Appeal.State.class, Objects.requireNonNull(rs.getString("state"))),
                rs.getString("filed_by"),
                requiredInstant(rs, "filed_at"),
                rs.getString("assignee_id"),
                instant(rs, "assigned_at"),
                rs.getString("decision_reason"),
                rs.getString("decided_by"),
                rs.getString("decided_role"),
                instant(rs, "decided_at"),
                rs.getLong("compensation_cents"),
                files(id));
    }

    private List<Appeal.AppealFile> files(String appealId) {
        return jdbc.sql("""
                        select id, name, content_type, size_bytes, storage_key, created_at
                          from standing.appeal_files where appeal_id = :id order by created_at, id
                        """)
                .param("id", appealId)
                .query((rs, _) -> new Appeal.AppealFile(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_key"),
                        requiredInstant(rs, "created_at")))
                .list();
    }
}
