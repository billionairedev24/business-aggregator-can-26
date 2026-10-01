package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;

import ca.northline.payments.api.CustomerEscrows;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CustomerEscrows} over {@code payments.escrows}, its open refund cases and disputes. */
@Repository
@RequiredArgsConstructor
class CustomerEscrowQueries implements CustomerEscrows {

    private final JdbcClient jdbc;

    @Override
    public List<EscrowFacts> of(String customerId, String refType, Collection<String> refIds) {
        if (refIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select e.id, e.ref_type, e.ref_id, e.merchant_id, coalesce(e.kind, 'goods') as kind,
                               coalesce(e.state, 'held') as state, coalesce(e.amount_cents, 0) as amount, e.tax_cents,
                               e.fulfilled_at, e.release_at,
                               exists (select 1 from payments.refunds r where r.escrow_id = e.id
                                        and r.state in ('requested', 'seller_review', 'agent_review', 'approved'))
                                 or exists (select 1 from payments.disputes d where d.ref_id = e.id
                                        and d.state in ('open', 'seller_replied', 'agent', 'appealed')) as open_case
                          from payments.escrows e
                         where e.customer_id = :c and e.ref_type = :type and e.ref_id in (:refs)
                        """)
                .param("c", customerId)
                .param("type", refType)
                .param("refs", List.copyOf(refIds))
                .query((rs, _) -> new EscrowFacts(
                        rs.getString("id"),
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        Objects.requireNonNullElse(rs.getString("merchant_id"), ""),
                        rs.getString("kind"),
                        rs.getString("state"),
                        rs.getLong("amount"),
                        rs.getLong("tax_cents"),
                        instant(rs, "fulfilled_at"),
                        instant(rs, "release_at"),
                        rs.getBoolean("open_case")))
                .list();
    }
}
