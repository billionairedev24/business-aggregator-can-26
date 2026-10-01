package ca.northline.account;

import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rows a consumer's account area reads: orders, bookings, quote requests, escrows and refund cases (S-58, S-60). */
record AccountFixtures(JdbcClient jdbc) {

    static java.time.OffsetDateTime ts(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    void storefront(String merchantId, String slug) {
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, published_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', now())
                        """).params(Ids.next(), merchantId, slug).update();
    }

    record Order(String id, List<String> lineIds) {}

    /** A goods order with one line per shop ({@code unit} cents, quantity 1). */
    Order goodsOrder(
            String customerId,
            String state,
            Instant placedAt,
            @Nullable Instant deliveredAt,
            List<String> shops,
            long unit) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, subtotal_cents, delivery_fee_cents,
                               service_fee_cents, tax_cents, tip_cents, delivery_kind, placed_at, delivered_at)
                        values (?, ?, ?, 'goods', ?, ?, 499, 0, ?, 0, 'pooled', ?, ?)
                        """)
                .params(
                        id,
                        "NL-7" + id.substring(19),
                        customerId,
                        state,
                        unit * shops.size(),
                        unit * shops.size() / 20,
                        ts(placedAt),
                        deliveredAt == null ? null : ts(deliveredAt))
                .update();
        var lines = new java.util.ArrayList<String>();
        for (var shop : shops) {
            var line = Ids.next();
            jdbc.sql("""
                            insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, title)
                            values (?, ?, ?, 1, ?, 'pending', 'Kale bunch')
                            """).params(line, id, shop, unit).update();
            lines.add(line);
        }
        return new Order(id, lines);
    }

    String booking(
            String customerId,
            String merchantId,
            @Nullable String memberId,
            String title,
            String state,
            Instant startsAt,
            long price,
            @Nullable String quoteId,
            long deposit) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.bookings (id, ref, customer_id, merchant_id, member_user_id, type, state,
                               starts_at, ends_at, title, price_cents, tax_cents, deposit_cents, quote_id, escrow_id, source)
                        values (?, ?, ?, ?, ?, 'visit', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'customer')
                        """)
                .params(
                        id,
                        "BK-9" + id.substring(20),
                        customerId,
                        merchantId,
                        memberId,
                        state,
                        ts(startsAt),
                        ts(startsAt.plus(Duration.ofHours(1))),
                        title,
                        price,
                        price / 20,
                        deposit,
                        quoteId,
                        "esc-" + id)
                .update();
        return id;
    }

    void completed(String bookingId, Instant at) {
        jdbc.sql("insert into booking.booking_events (id, booking_id, type, at) values (?, ?, 'completed', ?)")
                .params(Ids.next(), bookingId, ts(at))
                .update();
    }

    String quoteRequest(String customerId, String title, List<String> merchants) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.quote_requests (id, customer_id, category_id, details, merchant_ids, expires_at,
                               respond_by)
                        values (?, ?, 'service.events-and-hospitality.cocktail-and-mocktail-bar',
                                cast(? as jsonb), ?, now() + interval '7 days', now() + interval '2 hours')
                        """)
                .params(id, customerId, "{\"title\":\"" + title + "\"}", merchants.toArray(String[]::new))
                .update();
        return id;
    }

    String quote(String requestId, String merchantId, long total, String state) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.quotes (id, request_id, merchant_id, ref, version, scope, warranty, deposit_kind,
                               subtotal_cents, tax_cents, total_cents, valid_hours, valid_until, state, sent_at)
                        values (?, ?, ?, 'QT-1', 1, 'Bar for 40 guests', 'none', 'none', ?, 0, ?, 72,
                                now() + interval '3 days', ?, now())
                        """).params(id, requestId, merchantId, total, total, state).update();
        return id;
    }

    /** An escrow holding {@code amount} for a booking, an order line or a food order. */
    String escrow(
            String customerId,
            String merchantId,
            String kind,
            String refType,
            String refId,
            long amount,
            String state,
            @Nullable Instant fulfilledAt,
            @Nullable Instant releaseAt) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.escrows (id, ref_type, ref_id, merchant_id, amount_cents, release_at, state,
                               kind, label, customer_id, customer_name, take_rate_bps, fee_cents, tax_cents, occurred_at,
                               fulfilled_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'Kale bunch', ?, 'A. Osei', 1500, ?, ?, now(), ?)
                        """)
                .params(
                        id,
                        refType,
                        refId,
                        merchantId,
                        amount,
                        releaseAt == null ? null : ts(releaseAt),
                        state,
                        kind,
                        customerId,
                        amount * 15 / 100,
                        amount / 20,
                        fulfilledAt == null ? null : ts(fulfilledAt))
                .update();
        return id;
    }

    String refundCase(String merchantId, String escrowId, long amount, String state) {
        var id = Ids.next();
        var number = "RF-" + (5000 + Math.floorMod(id.hashCode(), 4000)) + id.substring(22);
        jdbc.sql("""
                        insert into payments.refunds (id, merchant_id, escrow_id, case_number, what, customer_name,
                               amount_cents, reason, charged_to, kind, auto, state, contest_by, created_at)
                        values (?, ?, ?, ?, 'Kale spoiled', 'A. Osei', ?, 'customer_request', 'merchant', 'refund', false, ?,
                                now() + interval '14 hours', now() - interval '10 hours')
                        """).params(id, merchantId, escrowId, number, amount, state).update();
        return id;
    }

    void points(String userId, int delta, Instant at) {
        jdbc.sql(
                        "insert into trust.points_ledger (id, user_id, delta, ref_type, created_at) values (?, ?, ?, 'order', ?)")
                .params(Ids.next(), userId, delta, ts(at))
                .update();
    }

    void plus(String userId, String plan) {
        var household = Ids.next();
        jdbc.sql("""
                        insert into identity.households (id, name, plus_plan, renews_at, plus_since)
                        values (?, 'Home', ?, now() + interval '20 days', now() - interval '100 days')
                        """).params(household, plan).update();
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (?, ?, 'owner')")
                .params(household, userId)
                .update();
    }
}
