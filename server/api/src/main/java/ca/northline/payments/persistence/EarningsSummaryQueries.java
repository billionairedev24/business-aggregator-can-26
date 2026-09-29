package ca.northline.payments.persistence;

import ca.northline.payments.api.EarningsSummary;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Minimal {@link EarningsSummary} over {@code payments.escrows / transfers / payouts / disputes / refunds}. Services =
 * escrows on bookings, parts = escrows on order lines. Added by the operations workstream (docs/DECISIONS.md
 * "Operations"); the finance workstream owns these tables.
 */
@Repository
@RequiredArgsConstructor
class EarningsSummaryQueries implements EarningsSummary {

    private final JdbcClient jdbc;

    @Override
    public long netBetween(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select coalesce(sum(t.net_cents), 0) from payments.transfers t
                          join payments.escrows e on e.id = t.escrow_id
                         where e.merchant_id = :merchantId and t.at >= :from and t.at < :to
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query(Long.class)
                .single();
    }

    @Override
    public List<WeekNet> weeklyNet(String merchantId, List<Instant> starts) {
        return starts.stream()
                .map(start -> {
                    var end = start.plus(Duration.ofDays(7));
                    return jdbc.sql("""
                                    select coalesce(sum(t.net_cents) filter (where e.ref_type = 'booking'), 0) as services,
                                           coalesce(sum(t.net_cents) filter (where e.ref_type = 'order_line'), 0) as parts
                                      from payments.transfers t join payments.escrows e on e.id = t.escrow_id
                                     where e.merchant_id = :merchantId and t.at >= :from and t.at < :to
                                    """)
                            .param("merchantId", merchantId)
                            .param("from", JdbcTimes.ts(start))
                            .param("to", JdbcTimes.ts(end))
                            .query((rs, _) -> new WeekNet(start, rs.getLong("services"), rs.getLong("parts")))
                            .single();
                })
                .toList();
    }

    @Override
    public Optional<Release> nextRelease(String merchantId, Instant now) {
        return jdbc.sql("""
                        select amount_cents, arrives_at from payments.payouts
                         where merchant_id = :merchantId and arrives_at > :now and coalesce(state, '') <> 'paid'
                         order by arrives_at limit 1
                        """)
                .param("merchantId", merchantId)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> new Release(rs.getLong("amount_cents"), JdbcTimes.requiredInstant(rs, "arrives_at")))
                .optional();
    }

    @Override
    public List<OpenCase> openCases(String merchantId) {
        return jdbc.sql("""
                        select 'dispute' as kind, d.id, e.ref_type, e.ref_id, d.opened_by as customer_id,
                               null::text as reason, 0 as ord
                          from payments.disputes d join payments.escrows e on e.id = d.ref_id
                         where e.merchant_id = :merchantId and d.state in ('open', 'seller_replied', 'agent', 'appealed')
                        union all
                        select 'refund', r.id, e.ref_type, e.ref_id, pi.customer_id, r.reason, 1
                          from payments.refunds r
                          join payments.escrows e on e.ref_id = coalesce(r.booking_id, r.order_line_id)
                          left join payments.payment_intents pi on pi.id = r.payment_intent_id
                         where e.merchant_id = :merchantId and r.state in ('requested', 'seller_review')
                         order by ord, id
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> new OpenCase(
                        rs.getString("kind"),
                        rs.getString("id"),
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        rs.getString("customer_id"),
                        rs.getString("reason")))
                .list();
    }

    @Override
    public Optional<Integer> refundRateBps(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) as sales, count(*) filter (where e.state = 'refunded') as refunded
                          from payments.escrows e
                         where e.merchant_id = :merchantId and e.ref_type = 'order_line'
                           and coalesce(e.released_at, e.release_at) >= :from and coalesce(e.released_at, e.release_at) < :to
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> {
                    long sales = rs.getLong("sales");
                    return sales == 0
                            ? Optional.<Integer>empty()
                            : Optional.of((int) (rs.getLong("refunded") * 10_000 / sales));
                })
                .single();
    }
}
