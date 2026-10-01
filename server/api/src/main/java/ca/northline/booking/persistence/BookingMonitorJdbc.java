package ca.northline.booking.persistence;

import ca.northline.booking.api.BookingMonitor;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link BookingMonitor} over {@code booking.bookings} (S-81). */
@Repository
@RequiredArgsConstructor
class BookingMonitorJdbc implements BookingMonitor {

    private final JdbcClient jdbc;

    @Override
    public List<MonitoredBooking> bookings(MerchantScope scope, Instant since, @Nullable String ref, int limit) {
        return jdbc.sql("""
                        select b.id, b.ref, b.state, b.customer_id, b.merchant_id, coalesce(b.price_cents, 0) as price,
                               b.created_at, b.starts_at, b.ends_at
                          from booking.bookings b
                         where (b.created_at >= :since or b.state not in ('signed_off', 'cancelled'))
                           and (:ref = '' or upper(b.ref) like :ref || '%')
                           and (:everyone or b.merchant_id = any(:merchants))
                         order by b.created_at desc, b.id desc
                         limit :limit
                        """)
                .param("since", JdbcTimes.ts(since))
                .param("ref", ref == null ? "" : ref.strip().toUpperCase(Locale.ROOT))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("limit", limit)
                .query((rs, _) -> new MonitoredBooking(
                        rs.getString("id"),
                        rs.getString("ref"),
                        rs.getString("state"),
                        rs.getString("customer_id"),
                        rs.getString("merchant_id"),
                        rs.getLong("price"),
                        JdbcTimes.requiredInstant(rs, "created_at"),
                        JdbcTimes.instant(rs, "starts_at"),
                        JdbcTimes.instant(rs, "ends_at")))
                .list();
    }
}
