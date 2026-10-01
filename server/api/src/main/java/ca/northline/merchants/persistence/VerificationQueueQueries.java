package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.VerificationQueue.Decision;
import ca.northline.merchants.application.VerificationQueue.DecisionRow;
import ca.northline.merchants.application.VerificationQueueStore;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.MerchantScope;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link VerificationQueueStore} over {@code merchants.merchants}, its checklist and {@code application_decisions}. */
@Repository
@RequiredArgsConstructor
class VerificationQueueQueries implements VerificationQueueStore {

    private static final String APPLICATION = """
            select m.id, m.display_name, m.legal_name, m.type, m.structure, m.province, m.city, m.status,
                   m.submitted_at,
                   coalesce(array(select c.category_id from merchants.merchant_categories c
                                   where c.merchant_id = m.id order by c.category_id), '{}') as categories,
                   d.decision, d.decided_at
              from merchants.merchants m
              left join lateral (select decision, decided_at from merchants.application_decisions x
                                  where x.merchant_id = m.id order by x.decided_at desc, x.id desc limit 1) d on true
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Application> applications(MerchantScope scope, Instant decidedSince, int limit) {
        return jdbc.sql(APPLICATION + """
                         where (:everyone or m.id = any(:merchants))
                           and (m.status = 'pending' or d.decided_at >= :since)
                         order by m.status = 'pending' desc,
                                  case when m.status = 'pending' then m.submitted_at end asc nulls last,
                                  d.decided_at desc, m.id
                         limit :n
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(decidedSince))
                .param("n", limit)
                .query((rs, _) -> application(rs))
                .list();
    }

    @Override
    public Optional<Application> application(String merchantId) {
        return jdbc.sql(APPLICATION + " where m.id = :m")
                .param("m", merchantId)
                .query((rs, _) -> application(rs))
                .optional();
    }

    @Override
    public Set<String> verificationsInReview(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        select distinct verification_id from merchants.registry_checks
                         where merchant_id = any(:m) and review_state = 'open'""")
                .param("m", merchantIds.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> identityInReview(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        select distinct merchant_id from merchants.owner_identity_checks
                         where merchant_id = any(:m) and status = 'review'""")
                .param("m", merchantIds.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    @Override
    public void insert(String merchantId, DecisionRow d, String role, @Nullable Instant submittedAt) {
        jdbc.sql("""
                        insert into merchants.application_decisions (id, merchant_id, decision, check_keys, note,
                               decided_by, role, submitted_at, decided_at)
                        values (:id, :m, :decision, :keys, :note, :by, :role, :submitted, :at)
                        """)
                .param("id", d.id())
                .param("m", merchantId)
                .param("decision", d.decision().code())
                .param("keys", d.checkKeys().toArray(String[]::new))
                .param("note", d.note())
                .param("by", d.decidedBy())
                .param("role", role)
                .param("submitted", ts(submittedAt))
                .param("at", ts(d.decidedAt()))
                .update();
    }

    @Override
    public List<DecisionRow> decisions(String merchantId) {
        return jdbc.sql("""
                        select id, decision, check_keys, note, decided_by, decided_at
                          from merchants.application_decisions where merchant_id = :m
                         order by decided_at desc, id desc""")
                .param("m", merchantId)
                .query((rs, _) -> new DecisionRow(
                        rs.getString("id"),
                        CodedEnum.fromCode(Decision.class, rs.getString("decision")),
                        strings(rs.getArray("check_keys")),
                        rs.getString("note"),
                        rs.getString("decided_by"),
                        requiredInstant(rs, "decided_at")))
                .list();
    }

    @Override
    public @Nullable Double medianDecisionHours(MerchantScope scope, Instant since) {
        return jdbc.sql("""
                        select percentile_cont(0.5) within group (
                                 order by extract(epoch from (d.decided_at - d.submitted_at)) / 3600.0)
                          from merchants.application_decisions d
                         where d.decided_at >= :since and d.submitted_at is not null
                           and (:everyone or d.merchant_id = any(:merchants))
                        """)
                .param("since", ts(since))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> {
                    var v = rs.getDouble(1);
                    return rs.wasNull() ? null : v;
                })
                .optional()
                .orElse(null);
    }

    private static Application application(ResultSet rs) throws SQLException {
        return new Application(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("legal_name"),
                rs.getString("type"),
                rs.getString("structure"),
                rs.getString("province"),
                rs.getString("city"),
                rs.getString("status"),
                instant(rs, "submitted_at"),
                strings(rs.getArray("categories")),
                CodedEnums.fromCode(rs.getString("decision"), Decision.class),
                instant(rs, "decided_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
