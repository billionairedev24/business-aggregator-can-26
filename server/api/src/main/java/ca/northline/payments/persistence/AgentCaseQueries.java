package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.api.AgentCases.AgentDecision;
import ca.northline.payments.api.AgentCases.Summary;
import ca.northline.payments.application.AgentCaseStore;
import ca.northline.shared.MerchantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AgentCaseStore} over {@code payments.disputes}, {@code payments.refunds} and {@code agent_decisions}. */
@Repository
@RequiredArgsConstructor
class AgentCaseQueries implements AgentCaseStore {

    private static final String DECISION = """
            select id, case_kind, case_id, merchant_id, outcome, refund_cents, note, decided_by, decided_at, state,
                   cosigned_by, cosigned_at, cosign_note
              from payments.agent_decisions""";

    private final JdbcClient jdbc;

    @Override
    public List<CaseRef> queue(MerchantScope scope, Instant decidedSince, int limit) {
        return jdbc.sql("""
                        select kind, id from (
                          select 'dispute' as kind, d.id, d.merchant_id, d.opened_at as at,
                                 d.state in ('agent', 'appealed') as waiting,
                                 (select max(a.decided_at) from payments.agent_decisions a
                                   where a.case_kind = 'dispute' and a.case_id = d.id) as decided_at
                            from payments.disputes d
                          union all
                          select 'refund', r.id, r.merchant_id, r.created_at, r.state = 'agent_review',
                                 (select max(a.decided_at) from payments.agent_decisions a
                                   where a.case_kind = 'refund' and a.case_id = r.id)
                            from payments.refunds r
                        ) c
                         where (:everyone or c.merchant_id = any(:merchants))
                           and (c.waiting or c.decided_at >= :since)
                         order by c.waiting desc, c.at asc, c.decided_at desc, c.id
                         limit :n
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", ts(decidedSince))
                .param("n", limit)
                .query((rs, _) -> new CaseRef(rs.getString("kind"), rs.getString("id")))
                .list();
    }

    @Override
    public Summary summary(MerchantScope scope, Instant weekAgo) {
        return jdbc.sql("""
                        select
                          (select count(*) from payments.disputes d where d.state in ('agent', 'appealed')
                              and (:everyone or d.merchant_id = any(:merchants)))
                          + (select count(*) from payments.refunds r where r.state = 'agent_review'
                              and (:everyone or r.merchant_id = any(:merchants))) as for_agent,
                          (select count(*) from payments.refunds r where r.state = 'seller_review'
                              and (:everyone or r.merchant_id = any(:merchants))) as seller_window,
                          (select count(*) from payments.disputes d where d.decided_at >= :week
                              and (:everyone or d.merchant_id = any(:merchants)))
                          + (select count(*) from payments.refunds r where r.decided_at >= :week
                              and r.dispute_id is null and r.state in ('approved', 'denied', 'paid')
                              and (:everyone or r.merchant_id = any(:merchants))) as closed
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("week", ts(weekAgo))
                .query((rs, _) ->
                        new Summary(rs.getLong("for_agent"), rs.getLong("seller_window"), rs.getLong("closed")))
                .single();
    }

    @Override
    public void insert(String kind, String caseId, String merchantId, AgentDecision d, String role) {
        jdbc.sql("""
                        insert into payments.agent_decisions (id, case_kind, case_id, merchant_id, outcome, refund_cents,
                               note, decided_by, role, decided_at, state)
                        values (:id, :kind, :case, :m, :outcome, :refund, :note, :by, :role, :at, :state)
                        """)
                .param("id", d.id())
                .param("kind", kind)
                .param("case", caseId)
                .param("m", merchantId)
                .param("outcome", d.outcome())
                .param("refund", d.refundCents())
                .param("note", d.note())
                .param("by", d.decidedBy())
                .param("role", role)
                .param("at", ts(d.decidedAt()))
                .param("state", d.state())
                .update();
    }

    @Override
    public Optional<StoredDecision> find(String decisionId) {
        return jdbc.sql(DECISION + " where id = :id")
                .param("id", decisionId)
                .query((rs, _) -> new StoredDecision(
                        rs.getString("case_kind"), rs.getString("case_id"), rs.getString("merchant_id"), decision(rs)))
                .optional();
    }

    @Override
    public Optional<AgentDecision> pending(String kind, String caseId) {
        return latest(kind, caseId, "awaiting_cosign");
    }

    @Override
    public Optional<AgentDecision> applied(String kind, String caseId) {
        return latest(kind, caseId, "applied");
    }

    private Optional<AgentDecision> latest(String kind, String caseId, String state) {
        return jdbc.sql(DECISION + " where case_kind = :kind and case_id = :case and state = :state"
                        + " order by decided_at desc, id desc limit 1")
                .param("kind", kind)
                .param("case", caseId)
                .param("state", state)
                .query((rs, _) -> decision(rs))
                .optional();
    }

    @Override
    public boolean cosign(
            String decisionId, String state, String staffId, String role, @Nullable String note, Instant at) {
        return jdbc.sql("""
                        update payments.agent_decisions
                           set state = :state, cosigned_by = :by, cosign_role = :role, cosigned_at = :at,
                               cosign_note = :note
                         where id = :id and state = 'awaiting_cosign'""")
                        .param("state", state)
                        .param("by", staffId)
                        .param("role", role)
                        .param("at", ts(at))
                        .param("note", note)
                        .param("id", decisionId)
                        .update()
                == 1;
    }

    @Override
    public int customerDisputes(String customerId, String exceptDisputeId) {
        return jdbc.sql("select count(*) from payments.disputes where opened_by = :c and id <> :id")
                .param("c", customerId)
                .param("id", exceptDisputeId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int[] merchantDisputes(String merchantId, String exceptDisputeId) {
        return jdbc.sql("""
                        select count(*) as n, count(*) filter (where decision = 'release') as won
                          from payments.disputes where merchant_id = :m and id <> :id""")
                .param("m", merchantId)
                .param("id", exceptDisputeId)
                .query((rs, _) -> new int[] {rs.getInt("n"), rs.getInt("won")})
                .single();
    }

    private static AgentDecision decision(ResultSet rs) throws SQLException {
        return new AgentDecision(
                rs.getString("id"),
                rs.getString("outcome"),
                rs.getLong("refund_cents"),
                rs.getString("note"),
                rs.getString("decided_by"),
                requiredInstant(rs, "decided_at"),
                rs.getString("state"),
                rs.getString("cosigned_by"),
                instant(rs, "cosigned_at"),
                rs.getString("cosign_note"));
    }
}
