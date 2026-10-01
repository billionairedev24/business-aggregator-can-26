package ca.northline.booking.persistence;

import ca.northline.booking.api.CustomerHistory;
import ca.northline.booking.api.CustomerHistory.OpenQuote;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CustomerHistory} over {@code booking.bookings}, its event log, quote requests and quotes. */
@Repository
@RequiredArgsConstructor
class CustomerHistoryJdbc implements CustomerHistory {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public List<BookingSummary> bookings(String customerId, int limit) {
        return jdbc.sql("""
                        select b.id, b.ref, b.merchant_id, b.member_user_id, coalesce(b.title, 'Job') as title,
                               coalesce(b.state, 'confirmed') as state, b.starts_at,
                               coalesce(b.ends_at, b.starts_at) as ends_at,
                               coalesce(b.price_cents, 0) + coalesce(b.tax_cents, 0) as total,
                               coalesce(b.deposit_cents, 0) as deposit, b.quote_id, b.escrow_id is not null as paid,
                               b.created_at,
                               (select max(e.at) from booking.booking_events e
                                 where e.booking_id = b.id and e.type = 'completed') as completed_at
                          from booking.bookings b
                         where b.customer_id = :c and b.starts_at is not null
                         order by b.starts_at desc, b.id desc
                         limit :limit
                        """)
                .param("c", customerId)
                .param("limit", limit)
                .query((rs, _) -> new BookingSummary(
                        rs.getString("id"),
                        Objects.requireNonNullElse(rs.getString("ref"), ""),
                        rs.getString("merchant_id"),
                        rs.getString("member_user_id"),
                        rs.getString("title"),
                        rs.getString("state"),
                        JdbcTimes.requiredInstant(rs, "starts_at"),
                        JdbcTimes.requiredInstant(rs, "ends_at"),
                        rs.getLong("total"),
                        rs.getLong("deposit"),
                        rs.getString("quote_id"),
                        JdbcTimes.instant(rs, "completed_at"),
                        rs.getBoolean("paid"),
                        JdbcTimes.requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public List<RequestSummary> openRequests(String customerId, int limit) {
        return jdbc.sql("""
                        with latest as (
                          select distinct on (q.request_id, q.merchant_id)
                                 q.request_id, q.merchant_id, q.id, q.state, q.total_cents, q.valid_until
                            from booking.quotes q
                           where q.state <> 'draft'
                           order by q.request_id, q.merchant_id, q.version desc
                        )
                        select r.id, r.number, coalesce(r.details ->> 'title', 'Quote request') as title, r.created_at,
                               (r.details ->> 'preferredAt')::timestamptz as preferred_at, r.expires_at,
                               coalesce(r.merchant_ids, '{}') as merchants,
                               coalesce((select array_agg(l.merchant_id order by l.total_cents) from latest l
                                          where l.request_id = r.id), '{}') as quoted,
                               coalesce((select array_agg(l.id order by l.total_cents, l.id) from latest l
                                          where l.request_id = r.id and l.state in ('sent', 'viewed')
                                            and (l.valid_until is null or l.valid_until > :now)), '{}') as open_quotes,
                               coalesce((select array_agg(l.merchant_id order by l.total_cents, l.id) from latest l
                                          where l.request_id = r.id and l.state in ('sent', 'viewed')
                                            and (l.valid_until is null or l.valid_until > :now)), '{}') as open_merchants,
                               (select min(l.total_cents) from latest l
                                 where l.request_id = r.id and l.state in ('sent', 'viewed')
                                   and (l.valid_until is null or l.valid_until > :now)) as lowest,
                               exists (select 1 from latest l where l.request_id = r.id)
                                 and not exists (select 1 from latest l where l.request_id = r.id
                                                  and l.state <> 'declined') as declined
                          from booking.quote_requests r
                         where r.customer_id = :c
                           and not exists (select 1 from booking.quotes q
                                            where q.request_id = r.id and q.state = 'accepted')
                         order by r.created_at desc, r.id desc
                         limit :limit
                        """)
                .param("c", customerId)
                .param("now", JdbcTimes.ts(clock.instant()))
                .param("limit", limit)
                .query((rs, _) -> new RequestSummary(
                        rs.getString("id"),
                        "QT-" + rs.getLong("number"),
                        rs.getString("title"),
                        JdbcTimes.requiredInstant(rs, "created_at"),
                        JdbcTimes.instant(rs, "preferred_at"),
                        JdbcTimes.instant(rs, "expires_at"),
                        strings(rs.getArray("merchants")),
                        strings(rs.getArray("quoted")),
                        openQuotes(strings(rs.getArray("open_quotes")), strings(rs.getArray("open_merchants"))),
                        rs.getObject("lowest") == null ? null : rs.getLong("lowest"),
                        rs.getBoolean("declined")))
                .list();
    }

    private static List<OpenQuote> openQuotes(List<String> ids, List<String> merchants) {
        return IntStream.range(0, Math.min(ids.size(), merchants.size()))
                .mapToObj(i -> new OpenQuote(ids.get(i), merchants.get(i)))
                .toList();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
