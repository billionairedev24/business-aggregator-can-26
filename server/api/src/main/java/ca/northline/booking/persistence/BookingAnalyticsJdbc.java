package ca.northline.booking.persistence;

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
 * S-95: bookings' share of the console's analytics — a booking counts as a purchase once confirmed (requested and
 * cancelled ones don't), when it was made.
 */
@Repository
@RequiredArgsConstructor
class BookingAnalyticsJdbc implements CustomerActivity {

    private static final String PAID = """
            b.customer_id is not null and b.state not in ('requested', 'cancelled')
            and (:everyone or b.merchant_id = any(:merchants))""";

    private final JdbcClient jdbc;

    @Override
    public List<Purchase> purchases(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("select b.customer_id, b.created_at from booking.bookings b where " + PAID
                        + " and b.created_at >= :from and b.created_at < :to")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new Purchase(
                        Objects.requireNonNull(rs.getString(1)), JdbcTimes.requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public Map<String, Long> salesByListing(MerchantScope scope, Instant from, Instant to) {
        var out = new HashMap<String, Long>();
        jdbc.sql("select b.service_id, sum(coalesce(b.price_cents, 0)) from booking.bookings b where " + PAID
                        + " and b.service_id is not null and b.created_at >= :from and b.created_at < :to"
                        + " group by b.service_id")
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(rs -> {
                    out.put(Objects.requireNonNull(rs.getString(1)), rs.getLong(2));
                });
        return out;
    }
}
