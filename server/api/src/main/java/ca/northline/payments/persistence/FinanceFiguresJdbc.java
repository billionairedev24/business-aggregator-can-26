package ca.northline.payments.persistence;

import ca.northline.payments.api.FinanceFigures;
import ca.northline.payments.domain.Tier;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FinanceFigures} over escrows, payouts, the ledger and the tax read model (S-85). */
@Repository
@RequiredArgsConstructor
class FinanceFiguresJdbc implements FinanceFigures {

    private final JdbcClient jdbc;

    @Override
    public Held escrowHeld() {
        return jdbc.sql("select coalesce(sum(amount_cents), 0) as c, count(*) as n from payments.escrows where state = 'held'")
                .query((rs, _) -> new Held(rs.getLong("c"), rs.getLong("n")))
                .single();
    }

    @Override
    public InFlight payoutsInFlight() {
        return jdbc.sql("""
                        select coalesce(sum(amount_cents), 0) as c, count(distinct merchant_id) as n, min(arrives_at) as next
                          from payments.payouts where state in ('pending', 'in_transit')""")
                .query((rs, _) -> new InFlight(rs.getLong("c"), rs.getLong("n"), JdbcTimes.instant(rs, "next")))
                .single();
    }

    @Override
    public Revenue revenue(Instant from, Instant to) {
        return jdbc.sql("""
                        select coalesce(sum(credit_cents - debit_cents) filter (where ref_type = 'escrow'), 0) as take,
                               coalesce(sum(credit_cents - debit_cents) filter (where ref_type = 'order_delivery'), 0) as delivery,
                               coalesce(sum(credit_cents - debit_cents)
                                        filter (where ref_type not in ('escrow', 'order_delivery')), 0) as adjustments
                          from payments.ledger_entries
                         where account = 'revenue' and at >= :from and at < :to""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new Revenue(rs.getLong("take"), rs.getLong("delivery"), rs.getLong("adjustments")))
                .single();
    }

    @Override
    public Map<String, Long> heldByMerchant(Instant from, Instant to) {
        var out = new HashMap<String, Long>();
        jdbc.sql("""
                        select merchant_id, sum(amount_cents) as c from payments.escrows
                         where created_at >= :from and created_at < :to and merchant_id is not null
                         group by merchant_id""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> out.put(rs.getString("merchant_id"), rs.getLong("c")))
                .list();
        return Map.copyOf(out);
    }

    @Override
    public Map<String, Integer> defaultTakeRates() {
        return Arrays.stream(Tier.values())
                .collect(Collectors.toUnmodifiableMap(t -> t.name().toLowerCase(Locale.ROOT), Tier::takeRateBps));
    }

    @Override
    public Tax tax(String period) {
        return jdbc.sql("""
                        select coalesce(sum(collected_cents) filter (where jurisdiction = 'platform_fee_gst'), 0) as fees,
                               coalesce(sum(collected_cents) filter (where jurisdiction <> 'platform_fee_gst'
                                                                     and handling = 'remitted_by_northline'), 0) as goods
                          from payments.tax_jurisdiction_totals where period = :p""")
                .param("p", period)
                .query((rs, _) -> new Tax(period, rs.getLong("fees"), rs.getLong("goods")))
                .single();
    }
}
