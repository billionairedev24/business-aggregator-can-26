package ca.northline.orders.persistence;

import ca.northline.orders.api.ShopFunnel;
import ca.northline.shared.CustomerActivity;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * S-95: orders' share of the console's analytics — paid orders (not cancelled) by customer, the shop funnel's counts
 * and sales by offer. A business is in scope when one of the order's lines is its.
 */
@Repository
@RequiredArgsConstructor
class OrderAnalyticsJdbc implements CustomerActivity, ShopFunnel {

    private static final String IN_SCOPE = """
            (:everyone or exists (select 1 from orders.order_lines sl
                                   where sl.order_id = o.id and sl.merchant_id = any(:merchants)))""";

    private final JdbcClient jdbc;

    @Override
    public List<Purchase> purchases(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("select o.customer_id, o.placed_at from orders.orders o"
                        + " where o.customer_id is not null and o.state <> 'cancelled'"
                        + " and o.placed_at >= :from and o.placed_at < :to and " + IN_SCOPE)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new Purchase(
                        Objects.requireNonNull(rs.getString(1)), JdbcTimes.requiredInstant(rs, "placed_at")))
                .list();
    }

    @Override
    public Map<String, Long> salesByListing(MerchantScope scope, Instant from, Instant to) {
        var out = new HashMap<String, Long>();
        jdbc.sql("""
                        select l.offer_id, sum(l.qty * l.unit_cents) as cents
                          from orders.order_lines l join orders.orders o on o.id = l.order_id
                         where l.offer_id is not null and l.state is distinct from 'refunded' and o.state <> 'cancelled'
                           and o.placed_at >= :from and o.placed_at < :to
                           and (:everyone or l.merchant_id = any(:merchants))
                         group by l.offer_id""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(rs -> {
                    out.put(Objects.requireNonNull(rs.getString(1)), rs.getLong(2));
                });
        return out;
    }

    @Override
    public Counts counts(MerchantScope scope, Instant from, Instant to) {
        Long carts = null;
        if (scope.everyone()) {
            carts = jdbc.sql("""
                            select count(distinct cart_id) from orders.cart_items
                             where added_at >= :from and added_at < :to""")
                    .param("from", JdbcTimes.ts(from))
                    .param("to", JdbcTimes.ts(to))
                    .query(Long.class)
                    .single();
        }
        var checkouts = jdbc.sql("""
                        select count(*) as started, count(*) filter (where c.state = 'placed') as paid
                          from orders.checkouts c
                         where c.created_at >= :from and c.created_at < :to
                           and (:everyone or exists (select 1 from jsonb_array_elements(c.lines) e
                                                      where e ->> 'merchantId' = any(:merchants)))""")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new long[] {rs.getLong("started"), rs.getLong("paid")})
                .single();
        return new Counts(carts, checkouts[0], checkouts[1]);
    }
}
