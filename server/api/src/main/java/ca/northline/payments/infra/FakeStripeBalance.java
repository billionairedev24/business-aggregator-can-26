package ca.northline.payments.infra;

import ca.northline.payments.application.StripeBalance;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link StripeBalance} without a Stripe key (local, test): what Stripe would hold if it agreed with the ledger — a
 * {@code charge} per captured PaymentIntent, a {@code refund} per paid refund and an {@code adjustment} per card
 * dispute, from the payments tables (fees 0) — plus whatever a test adds ({@link #add}) to make them disagree.
 */
class FakeStripeBalance implements StripeBalance {

    private final JdbcClient jdbc;
    private final List<Txn> extra = new CopyOnWriteArrayList<>();

    FakeStripeBalance(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tests: a transaction Stripe has and the ledger may not. */
    void add(Txn txn) {
        extra.add(txn);
    }

    @Override
    public List<Txn> between(Instant from, Instant to) {
        var out = new ArrayList<Txn>(jdbc.sql("""
                        select 'txn_' || md5(s.stripe_id) as id, s.type, s.stripe_id, s.cents, s.at
                          from (select case when l.ref_type = 'refund' then 'refund'
                                            when l.ref_type = 'dispute' then 'adjustment' else 'charge' end as type,
                                       coalesce(pi.stripe_charge, r.stripe_refund, d.stripe_dispute) as stripe_id,
                                       sum(l.debit_cents - l.credit_cents) as cents, min(l.at) as at
                                  from payments.ledger_entries l
                                  left join payments.escrows e on l.ref_type = 'escrow' and e.id = l.ref_id
                                  left join payments.payment_intents pi
                                         on pi.id = e.payment_intent_id
                                         or (l.ref_type = 'order_delivery' and pi.ref_type = 'order_delivery'
                                             and pi.ref_id = l.ref_id and pi.state = 'captured')
                                  left join payments.refunds r on l.ref_type = 'refund' and r.id = l.ref_id
                                  left join payments.disputes d on l.ref_type = 'dispute' and d.id = l.ref_id
                                 where l.account = 'stripe_balance' and l.at >= :from and l.at < :to
                                   and l.ref_type <> 'payout'
                                 group by 1, 2) s
                         where s.stripe_id is not null
                        """)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new Txn(
                        rs.getString("id"),
                        rs.getString("type"),
                        rs.getString("stripe_id"),
                        rs.getLong("cents"),
                        0,
                        JdbcTimes.requiredInstant(rs, "at")))
                .list());
        extra.stream()
                .filter(t -> !t.createdAt().isBefore(from) && t.createdAt().isBefore(to))
                .forEach(out::add);
        out.sort(Comparator.comparing(Txn::createdAt).thenComparing(Txn::id));
        return List.copyOf(out);
    }
}
