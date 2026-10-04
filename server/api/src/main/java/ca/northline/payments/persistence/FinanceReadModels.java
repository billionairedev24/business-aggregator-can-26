package ca.northline.payments.persistence;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.application.EarningsReadModel;
import ca.northline.payments.application.SalesReadModel;
import ca.northline.payments.domain.EscrowState;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL read models behind Earnings, Reports, tax documents and the dashboard. Months / weeks are the business's time zone. */
@Repository
@RequiredArgsConstructor
class FinanceReadModels implements EarningsReadModel, SalesReadModel {

    private final JdbcClient jdbc;

    @Override
    public EscrowTotals escrowTotals(String merchantId, @Nullable Instant releasingBy) {
        return jdbc.sql("""
                        select coalesce(sum(amount_cents - coalesce(fee_cents, 0)) filter (where state = 'held'), 0) as held_net,
                               count(*) filter (where state = 'held') as held_count,
                               coalesce(sum(amount_cents - coalesce(fee_cents, 0)) filter (
                                   where state = 'held' and release_at is not null
                                     and cast(:by as timestamptz) is not null and release_at <= :by), 0) as releasing,
                               coalesce(sum(amount_cents) filter (where state = 'disputed'), 0) as on_hold,
                               count(*) filter (where state = 'disputed') as on_hold_count
                          from payments.escrows where merchant_id = :m and state in ('held', 'disputed')""")
                .param("m", merchantId)
                .param("by", ts(releasingBy))
                .query((rs, _) -> new EscrowTotals(
                        rs.getLong("held_net"),
                        rs.getInt("held_count"),
                        rs.getLong("releasing"),
                        rs.getLong("on_hold"),
                        rs.getInt("on_hold_count")))
                .single();
    }

    @Override
    public RefundHolds refundHolds(String merchantId) {
        return jdbc.sql("""
                        select coalesce(sum(r.amount_cents), 0) as cents, count(*) as n
                          from payments.refunds r
                          join payments.escrows e on e.id = r.escrow_id
                         where r.merchant_id = :m and r.charged_to = 'merchant'
                           and r.state in ('seller_review', 'agent_review', 'approved')
                           and e.state = 'released'""")
                .param("m", merchantId)
                .query((rs, _) -> new RefundHolds(rs.getLong("cents"), rs.getInt("n")))
                .single();
    }

    @Override
    public List<LedgerLine> ledger(String merchantId, int limit) {
        return jdbc.sql("""
                        select id, kind, label, order_number, occurred_at, customer_name, amount_cents,
                               coalesce(tax_cents, 0) as tax_cents, coalesce(fee_cents, 0) as fee_cents, state,
                               release_at, released_at
                          from payments.escrows where merchant_id = :m
                         order by occurred_at desc, id desc limit :limit""")
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> new LedgerLine(
                        rs.getString("id"),
                        kind(rs),
                        rs.getString("label"),
                        rs.getString("order_number"),
                        instant(rs, "occurred_at"),
                        rs.getString("customer_name"),
                        rs.getLong("amount_cents") + rs.getLong("tax_cents"),
                        rs.getLong("amount_cents"),
                        rs.getLong("tax_cents"),
                        rs.getLong("fee_cents"),
                        rs.getLong("amount_cents") - rs.getLong("fee_cents"),
                        CodedEnum.fromCode(EscrowState.class, rs.getString("state")),
                        nullableInstant(rs, "release_at"),
                        nullableInstant(rs, "released_at")))
                .list();
    }

    @Override
    public List<Released> released(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select released_at, amount_cents - coalesce(fee_cents, 0) as net, kind
                          from payments.escrows
                         where merchant_id = :m and state = 'released' and released_at >= :from and released_at < :to""")
                .param("m", merchantId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, _) -> new Released(instant(rs, "released_at"), rs.getLong("net"), kind(rs)))
                .list();
    }

    @Override
    public List<Sale> sales(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select id, occurred_at, kind, label, order_number, customer_name, listing_name, source,
                               amount_cents, coalesce(fee_cents, 0) as fee_cents, tax_cents, state
                          from payments.escrows
                         where merchant_id = :m and occurred_at >= :from and occurred_at < :to
                         order by occurred_at, id""")
                .param("m", merchantId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, _) -> new Sale(
                        rs.getString("id"),
                        instant(rs, "occurred_at"),
                        kind(rs),
                        rs.getString("label"),
                        rs.getString("order_number"),
                        rs.getString("customer_name"),
                        rs.getString("listing_name"),
                        rs.getString("source"),
                        rs.getLong("amount_cents"),
                        rs.getLong("fee_cents"),
                        rs.getLong("tax_cents"),
                        CodedEnum.fromCode(EscrowState.class, rs.getString("state"))))
                .list();
    }

    @Override
    public long refunded(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select coalesce(sum(amount_cents), 0) from payments.refunds
                         where merchant_id = :m and state = 'paid' and created_at >= :from and created_at < :to""")
                .param("m", merchantId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    @Override
    public Customers customers(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        with period as (
                          select distinct customer_id from payments.escrows
                           where merchant_id = :m and customer_id is not null
                             and occurred_at >= :from and occurred_at < :to),
                        lifetime as (
                          select customer_id, count(*) as n from payments.escrows
                           where merchant_id = :m and customer_id in (select customer_id from period) and occurred_at < :to
                           group by customer_id)
                        select (select count(*) from period) as distinct_customers,
                               (select count(*) from lifetime where n >= 2) as repeat_customers""")
                .param("m", merchantId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, _) -> new Customers(rs.getInt("distinct_customers"), rs.getInt("repeat_customers")))
                .single();
    }

    @Override
    public OptionalInt benchmarkRefundBps(EscrowKind kind) {
        return jdbc.sql("select refund_rate_bps from payments.benchmarks where kind = :k")
                .param("k", kind.code())
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }

    @Override
    public DisputeRate disputeRate(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select (select count(*) from payments.disputes
                                 where merchant_id = :m and opened_at >= :from and opened_at < :to
                                   and (state <> 'decided' or decision in ('full_refund', 'partial'))) as counted,
                               (select count(*) from payments.escrows
                                 where merchant_id = :m and occurred_at >= :from and occurred_at < :to) as jobs""")
                .param("m", merchantId)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, _) -> new DisputeRate(rs.getInt("counted"), rs.getInt("jobs")))
                .single();
    }

    @Override
    public List<Month> months(String merchantId, int year, ZoneId zone) {
        return jdbc.sql("""
                        with months as (
                          select to_char(occurred_at at time zone :tz, 'YYYY-MM') as ym,
                                 sum(amount_cents) as gross, sum(coalesce(fee_cents, 0)) as fee, sum(tax_cents) as tax
                            from payments.escrows
                           where merchant_id = :m and extract(year from occurred_at at time zone :tz) = :y
                           group by 1),
                        refunds as (
                          select to_char(created_at at time zone :tz, 'YYYY-MM') as ym, sum(amount_cents) as refunded,
                                 sum(tax_cents) as tax_refunded
                            from payments.refunds
                           where merchant_id = :m and state = 'paid'
                             and extract(year from created_at at time zone :tz) = :y
                           group by 1),
                        payouts as (
                          select to_char(created_at at time zone :tz, 'YYYY-MM') as ym, sum(amount_cents) as paid
                            from payments.payouts
                           where merchant_id = :m and state in ('in_transit', 'paid')
                             and extract(year from created_at at time zone :tz) = :y
                           group by 1),
                        keys as (select ym from months union select ym from refunds union select ym from payouts)
                        select k.ym, coalesce(m.gross, 0) as gross, coalesce(m.fee, 0) as fee, coalesce(m.tax, 0) as tax,
                               coalesce(r.refunded, 0) as refunded, coalesce(r.tax_refunded, 0) as tax_refunded,
                               coalesce(p.paid, 0) as paid
                          from keys k left join months m using (ym) left join refunds r using (ym) left join payouts p using (ym)
                         order by k.ym""")
                .param("m", merchantId)
                .param("y", year)
                .param("tz", zone.getId())
                .query((rs, _) -> new Month(
                        YearMonth.parse(rs.getString("ym")),
                        rs.getLong("gross"),
                        rs.getLong("fee"),
                        rs.getLong("tax"),
                        rs.getLong("refunded"),
                        rs.getLong("tax_refunded"),
                        rs.getLong("paid")))
                .list();
    }

    private static EscrowKind kind(ResultSet rs) throws SQLException {
        var code = rs.getString("kind");
        return code == null ? EscrowKind.SERVICE : CodedEnum.fromCode(EscrowKind.class, code);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static java.time.@Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }
}
