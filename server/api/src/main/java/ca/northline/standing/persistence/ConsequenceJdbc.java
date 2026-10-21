package ca.northline.standing.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.shared.CodedEnum;
import ca.northline.standing.application.ConsequenceStore;
import ca.northline.standing.domain.Consequence;
import ca.northline.standing.domain.ConsequenceKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link ConsequenceStore} over {@code standing.consequences}. */
@Repository
@RequiredArgsConstructor
class ConsequenceJdbc implements ConsequenceStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private static final String SELECT = """
            select id, merchant_id, customer_id, kind, cause, reason, ref_id, detail, applied_by, applied_role,
                   started_at, ends_at, state, lifted_at, lifted_by, lifted_role, lift_reason
              from standing.consequences""";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(Consequence c) {
        return jdbc.sql("""
                        insert into standing.consequences (id, subject_type, merchant_id, customer_id, kind, cause,
                          reason, ref_id, detail, applied_by, applied_role, started_at, ends_at, state)
                        values (:id, :subject, :merchant, :customer, :kind, :cause, :reason, :ref,
                                cast(:detail as jsonb), :by, :role, :at, :ends, 'active')
                        on conflict do nothing
                        """)
                        .param("id", c.id())
                        .param("subject", c.merchantId() != null ? "business" : "customer")
                        .param("merchant", c.merchantId(), Types.VARCHAR)
                        .param("customer", c.customerId(), Types.VARCHAR)
                        .param("kind", c.kind().code())
                        .param("cause", c.cause())
                        .param("reason", c.reason())
                        .param("ref", c.refId(), Types.VARCHAR)
                        .param("detail", JSON.writeValueAsString(c.detail()))
                        .param("by", c.appliedBy())
                        .param("role", c.appliedRole())
                        .param("at", ts(c.startedAt()))
                        .param("ends", ts(c.endsAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update()
                > 0;
    }

    @Override
    public Optional<Consequence> find(String id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(ConsequenceJdbc::row)
                .optional();
    }

    @Override
    public Optional<Consequence> active(String subjectId, ConsequenceKind kind) {
        var column = kind.ofCustomer() ? "customer_id" : "merchant_id";
        return jdbc.sql(SELECT + " where " + column + " = :s and kind = :kind and state = 'active'"
                        + " order by started_at desc limit 1")
                .param("s", subjectId)
                .param("kind", kind.code())
                .query(ConsequenceJdbc::row)
                .optional();
    }

    @Override
    public Optional<Consequence> photoDelay(String bookingId) {
        return jdbc.sql(SELECT + " where kind = 'photo_delay' and ref_id = :b")
                .param("b", bookingId)
                .query(ConsequenceJdbc::row)
                .optional();
    }

    @Override
    public void lift(Consequence c) {
        jdbc.sql("""
                        update standing.consequences
                           set state = 'lifted', lifted_at = :at, lifted_by = :by, lifted_role = :role,
                               lift_reason = :reason
                         where id = :id and state = 'active'
                        """)
                .param("at", ts(c.liftedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("by", c.liftedBy(), Types.VARCHAR)
                .param("role", c.liftedRole(), Types.VARCHAR)
                .param("reason", c.liftReason(), Types.VARCHAR)
                .param("id", c.id())
                .update();
    }

    @Override
    public List<Consequence> ofBusiness(String merchantId, int limit) {
        return jdbc.sql(SELECT + " where merchant_id = :m order by started_at desc, id limit :n")
                .param("m", merchantId)
                .param("n", limit)
                .query(ConsequenceJdbc::row)
                .list();
    }

    @Override
    public List<Consequence> ofCustomer(String customerId, int limit) {
        return jdbc.sql(SELECT + " where customer_id = :c order by started_at desc, id limit :n")
                .param("c", customerId)
                .param("n", limit)
                .query(ConsequenceJdbc::row)
                .list();
    }

    @Override
    public List<Consequence> activeEndedBefore(ConsequenceKind kind, Instant at, int limit) {
        return jdbc.sql(SELECT + " where kind = :kind and state = 'active' and ends_at < :at order by ends_at limit :n")
                .param("kind", kind.code())
                .param("at", ts(at))
                .param("n", limit)
                .query(ConsequenceJdbc::row)
                .list();
    }

    @Override
    public Set<String> liftedByStaffSince(ConsequenceKind kind, Instant since) {
        return new HashSet<>(jdbc.sql("""
                        select distinct coalesce(merchant_id, customer_id) from standing.consequences
                         where kind = :kind and state = 'lifted' and lifted_at >= :since
                           and lifted_by is not null and lifted_by <> 'system'
                        """)
                .param("kind", kind.code())
                .param("since", ts(since))
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list());
    }

    private static Consequence row(ResultSet rs, int i) throws SQLException {
        return new Consequence(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("customer_id"),
                CodedEnum.fromCode(ConsequenceKind.class, java.util.Objects.requireNonNull(rs.getString("kind"))),
                rs.getString("cause"),
                rs.getString("reason"),
                rs.getString("ref_id"),
                map(rs.getString("detail")),
                rs.getString("applied_by"),
                rs.getString("applied_role"),
                requiredInstant(rs, "started_at"),
                instant(rs, "ends_at"),
                "active".equals(rs.getString("state")),
                instant(rs, "lifted_at"),
                rs.getString("lifted_by"),
                rs.getString("lifted_role"),
                rs.getString("lift_reason"));
    }

    static Map<String, String> map(@Nullable String json) {
        return json == null ? Map.of() : JSON.readValue(json, MAP);
    }
}
