package ca.northline.booking.persistence;

import ca.northline.booking.api.MarketplaceBookings;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketplaceBookings} over {@code booking.bookings} (S-91). */
@Repository
@RequiredArgsConstructor
class MarketplaceBookingsJdbc implements MarketplaceBookings {

    private static final String IN_SCOPE = "(:everyone or b.merchant_id = any(:merchants))";

    private final JdbcClient jdbc;

    @Override
    public List<Long> gmvCents(MerchantScope scope, Instant start, Duration length, int periods) {
        return jdbc.sql("""
                        select g.i, coalesce(sum(b.price_cents), 0) as cents
                          from generate_series(0, :periods - 1) g(i)
                          left join booking.bookings b
                                 on b.created_at >= :start + g.i * make_interval(secs => :seconds)
                                and b.created_at <  :start + (g.i + 1) * make_interval(secs => :seconds)
                                and b.state is distinct from 'cancelled' and %s
                         group by g.i order by g.i
                        """.formatted(IN_SCOPE))
                .param("periods", periods)
                .param("start", JdbcTimes.ts(start))
                .param("seconds", (double) length.toSeconds())
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> rs.getLong("cents"))
                .list();
    }

    @Override
    public long made(MerchantScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from booking.bookings b
                         where b.created_at >= :from and b.created_at < :to and b.state is distinct from 'cancelled'
                           and %s
                        """.formatted(IN_SCOPE))
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public long providersOnJobs(MerchantScope scope) {
        return jdbc.sql("""
                        select count(distinct coalesce(b.member_user_id, b.merchant_id)) from booking.bookings b
                         where b.state in ('en_route', 'on_site') and %s
                        """.formatted(IN_SCOPE))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }
}
