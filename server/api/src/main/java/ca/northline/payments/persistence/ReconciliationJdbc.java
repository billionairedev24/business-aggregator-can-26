package ca.northline.payments.persistence;

import ca.northline.payments.application.ReconcileStripe.Day;
import ca.northline.payments.application.ReconcileStripe.Item;
import ca.northline.payments.application.ReconciliationStore;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReconciliationStore} over the ledger, payouts and {@code payments.reconciliation_*} (S-85). */
@Repository
@RequiredArgsConstructor
class ReconciliationJdbc implements ReconciliationStore {

    private final JdbcClient jdbc;

    @Override
    public List<Posting> postings(Instant from, Instant to) {
        return jdbc.sql("""
                        select l.ref_type, l.ref_id, sum(l.debit_cents - l.credit_cents) as cents,
                               case l.ref_type
                                 when 'escrow' then (select pi.stripe_charge from payments.escrows e
                                                       join payments.payment_intents pi on pi.id = e.payment_intent_id
                                                      where e.id = l.ref_id)
                                 when 'refund' then (select r.stripe_refund from payments.refunds r where r.id = l.ref_id)
                                 when 'dispute' then (select d.stripe_dispute from payments.disputes d where d.id = l.ref_id)
                                 when 'payout' then (select p.stripe_payout from payments.payouts p where p.id = l.ref_id)
                                 else (select pi.stripe_charge from payments.payment_intents pi
                                        where pi.ref_type = l.ref_type and pi.ref_id = l.ref_id and pi.state = 'captured'
                                        order by pi.created_at desc limit 1)
                               end as stripe_id
                          from payments.ledger_entries l
                         where l.account = 'stripe_balance' and l.at >= :from and l.at < :to
                         group by l.ref_type, l.ref_id
                         order by l.ref_type, l.ref_id""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new Posting(
                        rs.getString("ref_type"), rs.getString("ref_id"), rs.getString("stripe_id"), rs.getLong("cents")))
                .list();
    }

    @Override
    public List<StripePayout> payouts(Instant from, Instant to) {
        return jdbc.sql("""
                        select id, stripe_payout, coalesce(amount_cents, 0) - coalesce(fee_cents, 0) as net
                          from payments.payouts
                         where stripe_payout is not null and created_at >= :from and created_at < :to
                           and state not in ('failed', 'canceled')
                         order by created_at, id""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new StripePayout(rs.getString("id"), rs.getString("stripe_payout"), rs.getLong("net")))
                .list();
    }

    @Override
    public Optional<Day> day(LocalDate day) {
        return jdbc.sql("select * from payments.reconciliation_days where day = :d")
                .param("d", day)
                .query((rs, _) -> day(rs))
                .optional();
    }

    @Override
    public List<Day> days(LocalDate from, LocalDate to) {
        return jdbc.sql("select * from payments.reconciliation_days where day between :f and :t order by day desc")
                .param("f", from)
                .param("t", to)
                .query((rs, _) -> day(rs))
                .list();
    }

    @Override
    public List<Item> items(LocalDate day) {
        return jdbc.sql("""
                        select * from payments.reconciliation_items where day = :d
                         order by status = 'matched', kind, coalesce(stripe_id, ''), id""")
                .param("d", day)
                .query((rs, _) -> new Item(
                        rs.getString("kind"),
                        rs.getString("stripe_id"),
                        rs.getObject("stripe_cents", Long.class),
                        rs.getString("ledger_ref_type"),
                        rs.getString("ledger_ref_id"),
                        rs.getObject("ledger_cents", Long.class),
                        rs.getString("status")))
                .list();
    }

    @Override
    public void save(Day day, Instant from, Instant to, List<Item> items) {
        jdbc.sql("""
                        insert into payments.reconciliation_days (day, from_at, to_at, stripe_cents, ledger_cents, fee_cents,
                                                                  items, mismatches, status, computed_at, resolved_note,
                                                                  resolved_by, resolved_at)
                        values (:day, :from, :to, :stripe, :ledger, :fee, :items, :mismatches, :status, :at, :note, :by, :resolvedAt)
                        on conflict (day) do update
                           set from_at = excluded.from_at, to_at = excluded.to_at, stripe_cents = excluded.stripe_cents,
                               ledger_cents = excluded.ledger_cents, fee_cents = excluded.fee_cents,
                               items = excluded.items, mismatches = excluded.mismatches, status = excluded.status,
                               computed_at = excluded.computed_at, resolved_note = excluded.resolved_note,
                               resolved_by = excluded.resolved_by, resolved_at = excluded.resolved_at""")
                .param("day", day.day())
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("stripe", day.stripeCents())
                .param("ledger", day.ledgerCents())
                .param("fee", day.feeCents())
                .param("items", day.items())
                .param("mismatches", day.mismatches())
                .param("status", day.status())
                .param("at", JdbcTimes.ts(day.computedAt()))
                .param("note", day.resolvedNote(), java.sql.Types.VARCHAR)
                .param("by", day.resolvedBy(), java.sql.Types.VARCHAR)
                .param("resolvedAt", JdbcTimes.ts(day.resolvedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("delete from payments.reconciliation_items where day = :d").param("d", day.day()).update();
        for (var i : items) {
            jdbc.sql("""
                            insert into payments.reconciliation_items (id, day, kind, stripe_id, stripe_cents, ledger_ref_type,
                                                                       ledger_ref_id, ledger_cents, status)
                            values (:id, :day, :kind, :sid, :sc, :rt, :rid, :lc, :status)""")
                    .param("id", Ids.next())
                    .param("day", day.day())
                    .param("kind", i.kind())
                    .param("sid", i.stripeId(), java.sql.Types.VARCHAR)
                    .param("sc", i.stripeCents(), java.sql.Types.BIGINT)
                    .param("rt", i.ledgerRefType(), java.sql.Types.VARCHAR)
                    .param("rid", i.ledgerRefId(), java.sql.Types.VARCHAR)
                    .param("lc", i.ledgerCents(), java.sql.Types.BIGINT)
                    .param("status", i.status())
                    .update();
        }
    }

    @Override
    public void resolve(LocalDate day, String note, String userId, Instant at) {
        jdbc.sql("""
                        update payments.reconciliation_days
                           set status = 'resolved', resolved_note = :note, resolved_by = :by, resolved_at = :at
                         where day = :d""")
                .param("note", note)
                .param("by", userId)
                .param("at", JdbcTimes.ts(at))
                .param("d", day)
                .update();
    }

    @Override
    public List<List<String>> ledger(Instant from, Instant to) {
        return jdbc.sql("""
                        select at, account, debit_cents, credit_cents, coalesce(ref_type, '') as ref_type,
                               coalesce(ref_id, '') as ref_id
                          from payments.ledger_entries where at >= :from and at < :to order by at, id""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> List.of(
                        JdbcTimes.requiredInstant(rs, "at").toString(),
                        rs.getString("account"),
                        Long.toString(rs.getLong("debit_cents")),
                        Long.toString(rs.getLong("credit_cents")),
                        rs.getString("ref_type"),
                        rs.getString("ref_id")))
                .list();
    }

    private static Day day(ResultSet rs) throws SQLException {
        return new Day(
                rs.getObject("day", LocalDate.class),
                rs.getLong("stripe_cents"),
                rs.getLong("ledger_cents"),
                rs.getLong("fee_cents"),
                rs.getInt("items"),
                rs.getInt("mismatches"),
                rs.getString("status"),
                JdbcTimes.requiredInstant(rs, "computed_at"),
                rs.getString("resolved_note"),
                rs.getString("resolved_by"),
                JdbcTimes.instant(rs, "resolved_at"));
    }
}
