package ca.northline.payments.persistence;

import ca.northline.payments.api.CustomerCaseQuery;
import static ca.northline.shared.JdbcTimes.instant;

import ca.northline.shared.JdbcTimes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CustomerCaseQuery}: refund cases are the customer's through their escrow ({@code escrows.customer_id}),
 * disputes through {@code disputes.opened_by}.
 */
@Repository
@RequiredArgsConstructor
class CustomerCaseQueries implements CustomerCaseQuery {

    private final JdbcClient jdbc;

    @Override
    public List<CaseSummary> cases(String customerId, int limit) {
        return query(customerId, null, limit);
    }

    @Override
    public Optional<CaseSummary> find(String customerId, String caseId) {
        return query(customerId, caseId, 1).stream().findFirst();
    }

    private List<CaseSummary> query(String customerId, @Nullable String caseId, int limit) {
        return jdbc.sql("""
                        select * from (
                          select r.id, 'refund' as kind, r.case_number as number, r.state, r.merchant_id,
                                 coalesce(r.what, '') as what, coalesce(r.amount_cents, 0) as amount,
                                 coalesce(r.tax_cents, 0) as tax, e.ref_type, e.ref_id, r.created_at as opened_at,
                                 case when r.state = 'seller_review' then r.contest_by end as respond_by,
                                 r.decided_at, r.paid_at,
                                 case when r.state in ('approved', 'paid') then r.kind
                                      when r.state = 'denied' then 'denied' end as outcome,
                                 case when r.state in ('approved', 'paid') then r.amount_cents end as settled,
                                 (r.contest_reason is not null or r.state = 'agent_review'
                                   or (r.decided_at is not null and r.contest_by is not null
                                       and r.decided_at >= r.contest_by)) as agent, r.contest_by as review_by
                            from payments.refunds r
                            join payments.escrows e on e.id = r.escrow_id
                           where e.customer_id = :c and r.dispute_id is null
                          union all
                          select d.id, 'dispute', d.case_number, d.state, d.merchant_id, coalesce(d.subject, ''),
                                 coalesce(d.amount_cents, 0), 0, e.ref_type, e.ref_id, d.opened_at,
                                 case when d.state = 'open' then d.respond_by end, d.decided_at, null,
                                 d.decision, d.refund_cents, d.state in ('agent', 'decided', 'appealed'), d.respond_by
                            from payments.disputes d
                            left join payments.escrows e on e.id = d.ref_id
                           where d.opened_by = :c
                        ) cases
                        where (cast(:id as text) is null or id = :id)
                        order by opened_at desc, id desc
                        limit :limit
                        """)
                .param("c", customerId)
                .param("id", caseId)
                .param("limit", limit)
                .query((rs, _) -> new CaseSummary(
                        rs.getString("id"),
                        rs.getString("kind"),
                        Objects.requireNonNullElse(rs.getString("number"), ""),
                        Objects.requireNonNullElse(rs.getString("state"), "seller_review"),
                        rs.getString("merchant_id"),
                        rs.getString("what"),
                        rs.getLong("amount"),
                        rs.getLong("tax"),
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        JdbcTimes.requiredInstant(rs, "opened_at"),
                        JdbcTimes.instant(rs, "respond_by"),
                        JdbcTimes.instant(rs, "decided_at"),
                        JdbcTimes.instant(rs, "paid_at"),
                        rs.getString("outcome"),
                        rs.getObject("settled") == null ? null : rs.getLong("settled"),
                        rs.getBoolean("agent"),
                        instant(rs, "review_by")))
                .list();
    }

}
