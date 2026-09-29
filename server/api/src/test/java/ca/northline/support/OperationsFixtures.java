package ca.northline.support;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL-level fixtures for the operations tests (jobs, quote requests, orders). Plain class — build it with the
 * autowired {@link JdbcClient} so no extra Spring context is created.
 */
public record OperationsFixtures(JdbcClient jdbc) {

    /** A confirmed, paid job for {@code memberUserId} starting at {@code startsAt} (45 min). */
    public String job(
            String merchantId, @Nullable String memberUserId, String customerId, Instant startsAt, String state) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.bookings (id, ref, customer_id, merchant_id, member_user_id, type, state, starts_at,
                               ends_at, title, address_line, area, details, escrow_id, price_cents)
                        values (:id, :ref, :customer, :merchant, :member, 'visit', :state, :starts, :ends, 'Brake inspection',
                               '1204 17 Ave SW', 'Beltline',
                               '{"vehicle":"2018 Honda Civic","access":"P2 stall 118","note":"Grinding on braking"}'::jsonb,
                               :escrow, 9345)
                        """)
                .param("id", id)
                .param("ref", "BK-" + id.substring(18))
                .param("customer", customerId)
                .param("merchant", merchantId)
                .param("member", memberUserId, java.sql.Types.VARCHAR)
                .param("state", state)
                .param("starts", JdbcTimes.ts(startsAt))
                .param("ends", JdbcTimes.ts(startsAt.plus(Duration.ofMinutes(45))))
                .param("escrow", Ids.next())
                .update();
        return id;
    }

    /** A quote request addressed to {@code merchantId}, respond within 2 h, valid 3 days. */
    public String quoteRequest(String merchantId, String customerId) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.quote_requests (id, customer_id, details, merchant_ids, expires_at, respond_by)
                        values (:id, :customer,
                                '{"title":"Alternator, 2016 Civic","description":"Battery light on","area":"Inglewood"}'::jsonb,
                                array[:merchant], now() + interval '3 days', now() + interval '2 hours')
                        """)
                .param("id", id)
                .param("customer", customerId)
                .param("merchant", merchantId)
                .update();
        return id;
    }

    /** A delivery window starting in {@code startsIn} with its cut-off 15 minutes before. */
    public String window(Duration startsIn, String runLabel) {
        var id = Ids.next();
        var start = Instant.now().plus(startsIn);
        jdbc.sql("""
                        insert into orders.delivery_windows (id, starts_at, ends_at, cutoff_at, capacity, run_label)
                        values (:id, :start, :end, :cutoff, 40, :label)
                        """)
                .param("id", id)
                .param("start", JdbcTimes.ts(start))
                .param("end", JdbcTimes.ts(start.plus(Duration.ofHours(2))))
                .param("cutoff", JdbcTimes.ts(start.minus(Duration.ofMinutes(15))))
                .param("label", runLabel)
                .update();
        return id;
    }

    /** An order with one line per (merchant, title, qty, unitCents, lineState). */
    public String order(String windowId, String customerId, String orderState, Line... lines) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, window_id, delivery_area)
                        values (:id, :ref, :customer, 'goods', :state, :window, 'Beltline')
                        """)
                .param("id", id)
                .param("ref", "NL-" + id.substring(20))
                .param("customer", customerId)
                .param("state", orderState)
                .param("window", windowId)
                .update();
        for (var l : lines) {
            jdbc.sql("""
                            insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, title)
                            values (:id, :order, :merchant, :qty, :unit, :state, :title)
                            """)
                    .param("id", Ids.next())
                    .param("order", id)
                    .param("merchant", l.merchantId())
                    .param("qty", l.qty())
                    .param("unit", l.unitCents())
                    .param("state", l.state())
                    .param("title", l.title())
                    .update();
        }
        return id;
    }

    public record Line(String merchantId, String title, int qty, long unitCents, String state) {}
}
