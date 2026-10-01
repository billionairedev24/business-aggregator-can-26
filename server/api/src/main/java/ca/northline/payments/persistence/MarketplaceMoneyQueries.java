package ca.northline.payments.persistence;

import ca.northline.payments.api.MarketplaceMoney;
import ca.northline.shared.Backlog;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketplaceMoney} over {@code payments.ledger_entries}, {@code escrows} and {@code disputes} (S-91). */
@Repository
@RequiredArgsConstructor
class MarketplaceMoneyQueries implements MarketplaceMoney {

    private final JdbcClient jdbc;

    @Override
    public long revenueCents(MerchantScope scope, Instant from, Instant to) {
        // Revenue entries point at the escrow released (credit) or the dispute refunded (debit); both carry the
        // business.
        return jdbc.sql("""
                        select coalesce(sum(coalesce(le.credit_cents, 0) - coalesce(le.debit_cents, 0)), 0)
                          from payments.ledger_entries le
                          left join payments.escrows e on le.ref_type = 'escrow' and e.id = le.ref_id
                          left join payments.disputes d on le.ref_type = 'dispute' and d.id = le.ref_id
                         where le.account = 'revenue' and le.at >= :from and le.at < :to
                           and (:everyone or coalesce(e.merchant_id, d.merchant_id) = any(:merchants))
                        """)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public long escrowHeldCents(MerchantScope scope) {
        return jdbc.sql("""
                        select coalesce(sum(e.amount_cents), 0) from payments.escrows e
                         where e.state = 'held' and (:everyone or e.merchant_id = any(:merchants))
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public long disputesOpened(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from payments.disputes d
                         where d.opened_at >= :from and d.opened_at < :to
                           and (:everyone or d.merchant_id = any(:merchants))
                        """)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public Backlog disputesForAgents(MerchantScope scope) {
        return jdbc.sql("""
                        select count(*) as n, min(d.opened_at) as oldest from payments.disputes d
                         where d.state in ('agent', 'appealed') and (:everyone or d.merchant_id = any(:merchants))
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new Backlog(rs.getLong("n"), JdbcTimes.instant(rs, "oldest")))
                .single();
    }
}
