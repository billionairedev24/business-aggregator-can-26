package ca.northline.booking.persistence;

import ca.northline.booking.api.BookingCalendar;
import ca.northline.booking.api.BookingInsights;
import ca.northline.booking.api.ServiceSales;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.NavBadgeContributor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The booking module's public reads ({@link BookingCalendar}, {@link BookingInsights}) and the Appointments sidebar
 * badge: the number of jobs today (America/Edmonton), e.g. "3".
 */
@Repository
@RequiredArgsConstructor
class BookingInsightsQueries implements BookingCalendar, BookingInsights, ServiceSales, NavBadgeContributor {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public List<Busy> busy(String merchantId, @Nullable String memberUserId, Instant from, Instant to) {
        return jdbc.sql("""
                        select id, member_user_id, starts_at, coalesce(ends_at, starts_at) as ends_at from booking.bookings
                         where merchant_id = :merchantId and state not in ('cancelled', 'requested')
                           and starts_at < :to and coalesce(ends_at, starts_at) > :from
                           and (cast(:member as text) is null or member_user_id = :member)
                         order by starts_at
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("member", memberUserId, java.sql.Types.VARCHAR)
                .query((rs, _) -> new Busy(
                        rs.getString("id"),
                        rs.getString("member_user_id"),
                        JdbcTimes.requiredInstant(rs, "starts_at"),
                        JdbcTimes.requiredInstant(rs, "ends_at")))
                .list();
    }

    @Override
    public List<Job> jobs(String merchantId, String memberUserId, Instant from, Instant to) {
        return jdbc.sql("""
                        select id, ref, coalesce(title, 'Job') as title, customer_id, address_line, starts_at,
                               coalesce(ends_at, starts_at + interval '1 hour') as ends_at
                          from booking.bookings
                         where merchant_id = :merchantId and member_user_id = :member
                           and state not in ('cancelled', 'requested')
                           and starts_at < :to and coalesce(ends_at, starts_at) > :from
                         order by starts_at, id
                        """)
                .param("merchantId", merchantId)
                .param("member", memberUserId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new Job(
                        rs.getString("id"),
                        rs.getString("ref"),
                        rs.getString("title"),
                        rs.getString("customer_id"),
                        rs.getString("address_line"),
                        JdbcTimes.requiredInstant(rs, "starts_at"),
                        JdbcTimes.requiredInstant(rs, "ends_at")))
                .list();
    }

    @Override
    public List<JobAtAGlance> jobs(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select id, coalesce(title, 'Job') as title, starts_at, state, customer_id, member_user_id, area,
                               details ->> 'access' as access,
                               case when escrow_id is not null and state not in ('signed_off', 'cancelled')
                                    then price_cents end as held
                          from booking.bookings
                         where merchant_id = :merchantId and starts_at >= :from and starts_at < :to and state <> 'cancelled'
                         order by starts_at, id
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> {
                    long heldValue = rs.getLong("held");
                    Long held = rs.wasNull() ? null : heldValue;
                    return new JobAtAGlance(
                            rs.getString("id"),
                            rs.getString("title"),
                            JdbcTimes.requiredInstant(rs, "starts_at"),
                            rs.getString("state"),
                            rs.getString("customer_id"),
                            rs.getString("member_user_id"),
                            rs.getString("area"),
                            rs.getString("access"),
                            held);
                })
                .list();
    }

    @Override
    public long jobCount(String merchantId, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from booking.bookings
                         where merchant_id = :merchantId and starts_at >= :from and starts_at < :to and state <> 'cancelled'
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query(Long.class)
                .single();
    }

    @Override
    public QuoteInbox quoteInbox(String merchantId, Instant now) {
        return jdbc.sql("""
                        select count(*) as open, min(r.respond_by) as earliest
                          from booking.quote_requests r
                         where :merchantId = any(r.merchant_ids) and (r.expires_at is null or r.expires_at > :now)
                           and not exists (select 1 from booking.quote_request_declines d
                                            where d.request_id = r.id and d.merchant_id = :merchantId)
                           and not exists (select 1 from booking.quotes q
                                            where q.request_id = r.id and q.merchant_id = :merchantId and q.state <> 'draft')
                        """)
                .param("merchantId", merchantId)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> {
                    return new QuoteInbox(rs.getInt("open"), JdbcTimes.instant(rs, "earliest"));
                })
                .single();
    }

    @Override
    public PhotoCoverage photoCoverage(String merchantId, int lastN) {
        return jdbc.sql("""
                        with last as (
                          select b.id from booking.bookings b
                           where b.merchant_id = :merchantId and b.state in ('completed', 'signed_off')
                           order by b.starts_at desc limit :n)
                        select count(*) as jobs,
                               count(*) filter (where not exists (
                                 select 1 from booking.booking_events e
                                  where e.booking_id = last.id and e.media_id is not null)) as without
                          from last
                        """)
                .param("merchantId", merchantId)
                .param("n", lastN)
                .query((rs, _) -> new PhotoCoverage(rs.getInt("jobs"), rs.getInt("without")))
                .single();
    }

    @Override
    public Optional<String> jobTitle(String bookingId) {
        return jdbc.sql("select title from booking.bookings where id = :id and title is not null")
                .param("id", bookingId)
                .query(String.class)
                .optional();
    }

    @Override
    public Map<String, String> badges(NavBadgeContributor.Context context) {
        var merchantId = context.merchantId();
        var today = LocalDate.now(clock.withZone(ZONE));
        long count = jobCount(
                merchantId,
                today.atStartOfDay(ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(ZONE).toInstant());
        return count == 0 ? Map.of() : Map.of("appointments", Long.toString(count));
    }

    @Override
    public Map<String, Long> bookingsByService(String merchantId, Instant from, Instant to) {
        return jdbc
                .sql("""
                        select service_id, count(*) as n from booking.bookings
                         where merchant_id = :m and service_id is not null and state <> 'cancelled'
                           and created_at >= :from and created_at < :to
                         group by service_id
                        """)
                .param("m", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> Map.entry(rs.getString("service_id"), rs.getLong("n")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
