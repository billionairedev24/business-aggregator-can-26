package ca.northline.worker.notifications;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link Subjects} as read-only queries on the api's tables ({@code orders.orders}, {@code booking.bookings},
 * {@code booking.quote_requests}, {@code payments.refunds} + {@code payments.escrows}, {@code fulfilment.couriers}) —
 * the same choice as {@link JdbcRecipients} (DECISIONS S-27): no service credentials, no extra hop.
 */
public final class JdbcSubjects implements Subjects {

    private final JdbcClient jdbc;

    public JdbcSubjects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Order> order(String orderId) {
        return jdbc.sql("select customer_id, type from orders.orders where id = :id and customer_id is not null")
                .param("id", orderId)
                .query((rs, _) ->
                        new Order(rs.getString("customer_id"), "food".equals(rs.getString("type")) ? "food" : "goods"))
                .optional();
    }

    @Override
    public Optional<Booking> booking(String bookingId) {
        return jdbc.sql("""
                        select customer_id, merchant_id, starts_at from booking.bookings
                         where id = :id and customer_id is not null""")
                .param("id", bookingId)
                .query((rs, _) -> {
                    var starts = rs.getObject("starts_at", OffsetDateTime.class);
                    return new Booking(
                            rs.getString("customer_id"),
                            rs.getString("merchant_id"),
                            starts == null ? null : starts.toInstant());
                })
                .optional();
    }

    @Override
    public Optional<String> quoteCustomer(String requestId) {
        return jdbc.sql("select customer_id from booking.quote_requests where id = :id and customer_id is not null")
                .param("id", requestId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> refundCustomer(String refundId) {
        return jdbc.sql("""
                        select coalesce(e.customer_id, b.customer_id)
                          from payments.refunds r
                          left join payments.escrows e on e.id = r.escrow_id
                          left join booking.bookings b on b.id = r.booking_id
                         where r.id = :id""").param("id", refundId).query(String.class).optional();
    }

    @Override
    public Optional<String> courierUser(String courierId) {
        return jdbc.sql("select user_id from fulfilment.couriers where id = :id and user_id is not null")
                .param("id", courierId)
                .query(String.class)
                .optional();
    }

    @Override
    public List<String> confirmedBookingsStarting(Instant from, Instant to) {
        return jdbc.sql("""
                        select id from booking.bookings
                         where state = 'confirmed' and customer_id is not null
                           and starts_at >= :from and starts_at < :to
                         order by starts_at""")
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, _) -> rs.getString(1))
                .list();
    }
}
