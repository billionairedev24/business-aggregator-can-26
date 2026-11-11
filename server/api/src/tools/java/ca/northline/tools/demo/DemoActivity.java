package ca.northline.tools.demo;

import ca.northline.tools.demo.DemoBusinesses.Made;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The marketplace's history and today's work (S-194), dated around the moment the seed runs so "today", "tonight's
 * run" and "respond within" always have something: ratings and reviews on every business; the demo customer's past
 * orders, bookings (signed off and reviewed) and an upcoming visit; the demo owner's jobs today, orders to pack on
 * tonight's pooled run, an open quote request, a dispute, low stock, an insurance renewal due and twelve weeks of
 * released earnings; for the console, a dispute escalated to an agent and a flagged listing. Rows a re-run finds are
 * moved to the new "now" (today's jobs stay today's); reviews are immutable once written (V073) and stay as they are.
 */
final class DemoActivity {

    private static final String[] PROVIDER_REVIEWS = {
        "On time, explained everything and left the place tidy. Booking again.",
        "Clear price up front and no surprises. Very professional.",
        "Quick to reply, easy to book and great work.",
        "Did exactly what was promised, a little late but kept me posted.",
        "Friendly, careful and fair. Highly recommend.",
        "Second time booking — just as good as the first.",
    };
    private static final String[] SHOP_REVIEWS = {
        "Arrived on tonight's run, well packed and fresh.",
        "Great quality and the delivery window was spot on.",
        "Exactly as described. Will order again.",
        "One item was substituted but the shop messaged first. Happy overall.",
        "Love supporting a local shop that delivers this fast.",
    };
    private static final String[] KITCHEN_REVIEWS = {
        "Still hot when it arrived. Generous portions.",
        "Our new Friday-night spot. Everything was delicious.",
        "Fast pickup and the food was excellent.",
        "Tasty, but the delivery took a little longer than the estimate.",
        "Fresh, flavourful and well packed.",
    };

    private final Db db;
    private final DemoAccounts accounts;
    private final DemoMarket market;
    private final Instant now;
    private final LocalDate today;
    private final Map<String, Integer> counts = new TreeMap<>();

    DemoActivity(Db db, DemoAccounts accounts, DemoMarket market, Instant now) {
        this.db = db;
        this.accounts = accounts;
        this.market = market;
        this.now = now;
        this.today = LocalDate.ofInstant(now, market.zone());
    }

    Map<String, Integer> counts() {
        return counts;
    }

    void seed(List<Made> made, int reviewers, @Nullable String flaggedSellerKey) {
        for (var m : made) {
            reviews(m, reviewers);
        }
        var evening = window("evening", today, LocalTime.of(18, 0), LocalTime.of(21, 0), LocalTime.of(17, 45));
        var morning =
                window("morning", today.plusDays(1), LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(7, 30));
        var lastNight =
                window("evening", today.minusDays(1), LocalTime.of(18, 0), LocalTime.of(21, 0), LocalTime.of(17, 45));
        var customer = accounts.id("customer");
        var customer2 = accounts.id("customer2");
        // ── the demo customer's history ──
        var bakery = find(made, "bakery");
        var grocer = find(made, "grocer");
        var barber = find(made, "barber");
        var cleaning = find(made, "cleaning");
        var pho = find(made, "pho");
        if (bakery != null && grocer != null) {
            goodsOrder(
                    "history-1",
                    customer,
                    "customer",
                    lastNight,
                    "delivered",
                    now.minus(Duration.ofDays(1)),
                    List.of(line(bakery, 0, 1), line(bakery, 1, 1), line(grocer, 0, 2)));
            goodsOrder(
                    "history-2",
                    customer,
                    "customer",
                    lastNight,
                    "delivered",
                    now.minus(Duration.ofDays(9)),
                    List.of(line(grocer, 1, 1), line(grocer, 3, 1)));
        }
        if (barber != null) {
            booking("history-cut", customer, barber, 0, today.minusDays(14), LocalTime.of(10, 0), "signed_off", true);
            booking("upcoming-cut", customer, barber, 1, today.plusDays(3), LocalTime.of(11, 0), "confirmed", false);
        }
        if (cleaning != null) {
            booking("history-clean", customer, cleaning, 0, today.minusDays(6), LocalTime.of(9, 0), "signed_off", true);
        }
        if (pho != null) {
            foodOrder("history-pho", customer, pho, now.minus(Duration.ofDays(3)));
        }
        // ── the demo owner's work today (every business marked "owner" in the plan) ──
        int c = 0;
        for (var m : made) {
            if (!"owner".equals(m.business().owner())) {
                continue;
            }
            if (m.business().provider()) {
                booking(
                        "today-1-" + m.business().key(),
                        customer2,
                        m,
                        0,
                        today,
                        LocalTime.of(9, 30),
                        "confirmed",
                        false);
                booking(
                        "today-2-" + m.business().key(),
                        reviewer(c++, reviewers),
                        m,
                        1 % m.listingIds().size(),
                        today,
                        LocalTime.of(13, 0),
                        "confirmed",
                        false);
                booking(
                        "today-3-" + m.business().key(),
                        reviewer(c++, reviewers),
                        m,
                        2 % m.listingIds().size(),
                        today,
                        LocalTime.of(16, 0),
                        "confirmed",
                        false);
                booking(
                        "tomorrow-" + m.business().key(),
                        reviewer(c++, reviewers),
                        m,
                        0,
                        today.plusDays(1),
                        LocalTime.of(10, 0),
                        "confirmed",
                        false);
                booking(
                        "yesterday-" + m.business().key(),
                        reviewer(c++, reviewers),
                        m,
                        1 % m.listingIds().size(),
                        today.minusDays(1),
                        LocalTime.of(11, 0),
                        "completed",
                        false);
                quoteRequest(m, reviewer(c++, reviewers));
                earnings(m, "service");
            } else if (m.business().seller()) {
                goodsOrder(
                        "run-1-" + m.business().key(),
                        customer2,
                        "customer2",
                        evening,
                        "placed",
                        now.minus(Duration.ofHours(2)),
                        List.of(line(m, 0, 2), line(m, 1, 1)));
                goodsOrder(
                        "run-2-" + m.business().key(),
                        reviewer(c++, reviewers),
                        null,
                        evening,
                        "placed",
                        now.minus(Duration.ofMinutes(50)),
                        List.of(line(m, 2 % m.listingIds().size(), 1)));
                goodsOrder(
                        "run-3-" + m.business().key(),
                        reviewer(c++, reviewers),
                        null,
                        morning,
                        "placed",
                        now.minus(Duration.ofMinutes(20)),
                        List.of(line(m, 0, 1)));
                earnings(m, "goods");
            } else {
                earnings(m, "food");
            }
        }
        var mechanic = find(made, "mechanic");
        if (mechanic != null) {
            dispute(
                    "studio",
                    mechanic,
                    reviewer(1, reviewers),
                    "open",
                    "Brake inspection",
                    "The squeal came back two days after the inspection.",
                    now.minus(Duration.ofHours(20)));
            db.update(
                    "update merchants.verifications set expires_at = ? where id = ?",
                    now.plus(Duration.ofDays(18)),
                    DemoIds.of("verification:" + mechanic.merchantId() + ":insurance"));
        }
        var handyman = find(made, "handyman");
        if (handyman != null) {
            dispute(
                    "console",
                    handyman,
                    reviewer(2, reviewers),
                    "agent",
                    "TV wall mounting",
                    "The TV was mounted crooked and the provider won't come back to fix it.",
                    now.minus(Duration.ofDays(2)));
        }
        if (flaggedSellerKey != null) {
            var seller = find(made, flaggedSellerKey);
            if (seller != null) {
                flag(seller);
            }
        }
    }

    // ── reviews ─────────────────────────────────────────────────────────────────────────────────────────────────

    private void reviews(Made m, int reviewers) {
        var b = m.business();
        var n = 3 + Math.floorMod(b.key().hashCode(), 8);
        var pool = b.provider() ? PROVIDER_REVIEWS : b.seller() ? SHOP_REVIEWS : KITCHEN_REVIEWS;
        for (int i = 0; i < n; i++) {
            var h = Math.floorMod((b.key() + i).hashCode(), 100);
            var rating = h < 62 ? 5 : h < 90 ? 4 : 3;
            var author = DemoAccounts.reviewerId(Math.floorMod(b.key().hashCode() + i, reviewers));
            var at = now.minus(Duration.ofDays(2L + i * 5L + Math.floorMod(h, 4)));
            var refType = b.provider() ? "booking" : "order";
            db.update(
                    """
                    insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, tags, text, reply, lang,
                        author_name, job_label, created_at, reply_at, reply_by)
                    select ?, ?, ?, ?, 'merchant', ?, ?, '{}', ?, ?, 'en', u.first_name || ' ' || left(u.last_name, 1) || '.', ?, ?, ?, ?
                      from identity.users u where u.id = ?
                    on conflict do nothing
                    """,
                    DemoIds.of("review:" + b.key() + ":" + i),
                    refType,
                    "DEMO-" + b.key() + "-" + i,
                    author,
                    m.merchantId(),
                    rating,
                    pool[Math.floorMod(h, pool.length)],
                    i % 3 == 0 ? "Thank you — it was a pleasure!" : null,
                    b.listings().isEmpty()
                            ? b.name()
                            : b.listings().get(i % b.listings().size()).title(),
                    at,
                    i % 3 == 0 ? at.plus(Duration.ofHours(5)) : null,
                    i % 3 == 0 ? m.merchantId() : null,
                    author);
            count("reviews");
        }
    }

    // ── pooled runs and orders ──────────────────────────────────────────────────────────────────────────────────

    private record Line(Made merchant, int listing, int qty) {}

    private static Line line(Made m, int listing, int qty) {
        return new Line(m, listing, qty);
    }

    /** A run of the market's pooled delivery (the api's default schedule: evening 18–21, cut-off 17:45; morning 8–11). */
    private String window(String slot, LocalDate day, LocalTime from, LocalTime to, LocalTime cutoff) {
        var start = day.atTime(from).atZone(market.zone()).toInstant();
        var existing = db.text(
                "select id from orders.delivery_windows where (market = ? or market is null) and starts_at = ? order by market nulls last limit 1",
                market.id(),
                start);
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = DemoIds.of("window:" + market.id() + ":" + slot + ":" + day);
        db.update(
                """
                insert into orders.delivery_windows (id, starts_at, ends_at, cutoff_at, capacity, run_label, market, slot)
                values (?, ?, ?, ?, 40, ?, ?, ?) on conflict (id) do nothing
                """,
                id,
                start,
                day.atTime(to).atZone(market.zone()).toInstant(),
                day.atTime(cutoff).atZone(market.zone()).toInstant(),
                "R-" + day.getDayOfYear() + ("evening".equals(slot) ? "E" : "M"),
                market.id(),
                slot);
        return id;
    }

    private void goodsOrder(
            String key,
            String customerId,
            @Nullable String accountKey,
            String windowId,
            String state,
            Instant placed,
            List<Line> lines) {
        var id = DemoIds.of("order:" + key);
        long subtotal = 0;
        for (var l : lines) {
            subtotal += unit(l) * l.qty();
        }
        var delivered = "delivered".equals(state);
        db.update(
                """
                insert into orders.orders (id, ref, customer_id, type, state, address_id, window_id, subtotal_cents, delivery_fee_cents,
                    service_fee_cents, tax_cents, tip_cents, placed_at, delivered_at, delivery_area, fulfilment_mode, delivery_kind)
                values (?, ?, ?, 'goods', ?, ?, ?, ?, 299, 99, ?, 0, ?, ?, ?, 'delivery', 'pooled')
                on conflict (id) do update set state = excluded.state, window_id = excluded.window_id, placed_at = excluded.placed_at,
                    delivered_at = excluded.delivered_at
                """,
                id,
                "NL-D" + id.substring(20),
                customerId,
                state,
                accountKey == null ? null : DemoAccounts.addressId(accountKey),
                windowId,
                subtotal,
                Math.round(subtotal * 0.05),
                placed,
                delivered ? placed.plus(Duration.ofHours(5)) : null,
                DemoMarket.QUARTERS.getFirst());
        int i = 0;
        for (var l : lines) {
            var lineId = DemoIds.of("order-line:" + key + ":" + i);
            var offer = l.merchant().listingIds().get(l.listing());
            var title = l.merchant().business().listings().get(l.listing()).title();
            db.update(
                    """
                    insert into orders.order_lines (id, order_id, merchant_id, offer_id, qty, unit_cents, state, title, packed_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do update set state = excluded.state, packed_at = excluded.packed_at
                    """,
                    lineId,
                    id,
                    l.merchant().merchantId(),
                    offer,
                    l.qty(),
                    unit(l),
                    delivered ? "packed" : "pending",
                    title,
                    delivered ? placed.plus(Duration.ofHours(2)) : null);
            escrow(
                    "order-line:" + key + ":" + i,
                    "order_line",
                    lineId,
                    l.merchant(),
                    unit(l) * l.qty(),
                    "goods",
                    title,
                    "NL-D" + id.substring(20),
                    customerId,
                    offer,
                    placed,
                    delivered ? "released" : "held",
                    delivered ? placed.plus(Duration.ofDays(7)) : null);
            i++;
        }
        count(delivered ? "past orders" : "orders to pack");
    }

    private long unit(Line l) {
        var p = l.merchant().business().listings().get(l.listing()).priceCents();
        return p == null ? 1000 : p;
    }

    private void foodOrder(String key, String customerId, Made kitchen, Instant placed) {
        var id = DemoIds.of("order:" + key);
        var items = kitchen.listingIds();
        var dish = kitchen.business().menuSections().getFirst().items().getFirst();
        var main = kitchen.business().menuSections().size() > 1
                ? kitchen.business().menuSections().get(1).items().getFirst()
                : dish;
        var subtotal = dish.priceCents() + main.priceCents() * 2;
        db.update(
                """
                insert into orders.orders (id, ref, customer_id, type, state, address_id, subtotal_cents, delivery_fee_cents, service_fee_cents,
                    tax_cents, tip_cents, placed_at, delivered_at, delivery_area, fulfilment_mode, delivery_kind)
                values (?, ?, ?, 'food', 'delivered', ?, ?, 499, 99, ?, 300, ?, ?, ?, 'delivery', 'direct')
                on conflict (id) do update set placed_at = excluded.placed_at, delivered_at = excluded.delivered_at
                """,
                id,
                "NL-F" + id.substring(20),
                customerId,
                DemoAccounts.addressId("customer"),
                subtotal,
                Math.round(subtotal * 0.05),
                placed,
                placed.plus(Duration.ofMinutes(42)),
                DemoMarket.QUARTERS.getFirst());
        db.update(
                """
                insert into orders.order_lines (id, order_id, merchant_id, menu_item_id, qty, unit_cents, state, title)
                values (?, ?, ?, ?, 1, ?, 'packed', ?), (?, ?, ?, ?, 2, ?, 'packed', ?) on conflict (id) do nothing
                """,
                DemoIds.of("order-line:" + key + ":0"),
                id,
                kitchen.merchantId(),
                items.getFirst(),
                dish.priceCents(),
                dish.name(),
                DemoIds.of("order-line:" + key + ":1"),
                id,
                kitchen.merchantId(),
                items.get(Math.min(2, items.size() - 1)),
                main.priceCents(),
                main.name());
        escrow(
                "food:" + key,
                "food_order",
                id,
                kitchen,
                subtotal,
                "food",
                kitchen.business().name(),
                "NL-F" + id.substring(20),
                customerId,
                null,
                placed,
                "released",
                placed.plus(Duration.ofMinutes(42)));
        count("past orders");
    }

    // ── bookings and quotes ─────────────────────────────────────────────────────────────────────────────────────

    private void booking(
            String key,
            String customerId,
            Made provider,
            int listing,
            LocalDate day,
            LocalTime at,
            String state,
            boolean reviewed) {
        var b = provider.business();
        var l = b.listings().get(listing);
        var id = DemoIds.of("booking:" + key);
        var starts = day.atTime(at).atZone(market.zone()).toInstant();
        var ends = starts.plus(Duration.ofMinutes(l.durationMin() == null ? 60 : l.durationMin()));
        var price = l.priceCents() == null ? 10000 : l.priceCents();
        var tax = Math.round(price * 0.05);
        var ref = "BK-D" + id.substring(22);
        var escrowId = escrow(
                "booking:" + key,
                "booking",
                id,
                provider,
                price,
                "service",
                l.title(),
                null,
                customerId,
                provider.listingIds().get(listing),
                starts,
                "signed_off".equals(state) ? "released" : "held",
                "signed_off".equals(state) ? ends.plus(Duration.ofDays(2)) : null);
        var addressAccount = customerId.equals(accounts.id("customer"))
                ? "customer"
                : customerId.equals(accounts.id("customer2")) ? "customer2" : null;
        db.update(
                """
                insert into booking.bookings (id, ref, customer_id, merchant_id, member_user_id, service_id, type, state, starts_at, ends_at,
                    title, address_id, address_line, area, details, escrow_id, price_cents, tax_cents, source, created_at, updated_at,
                    free_cancel_until)
                select ?, ?, ?, ?, mm.user_id, ?, 'visit', ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, 'customer', ?, ?, ?
                  from merchants.merchant_members mm where mm.merchant_id = ? and mm.role = 'owner' limit 1
                on conflict (id) do update set state = excluded.state, starts_at = excluded.starts_at, ends_at = excluded.ends_at,
                    free_cancel_until = excluded.free_cancel_until, updated_at = excluded.updated_at
                """,
                id,
                ref,
                customerId,
                provider.merchantId(),
                provider.listingIds().get(listing),
                state,
                starts,
                ends,
                l.title(),
                addressAccount == null ? null : DemoAccounts.addressId(addressAccount),
                addressAccount == null ? "Demo address" : null,
                DemoMarket.QUARTERS.get(Math.floorMod(key.hashCode(), DemoMarket.QUARTERS.size())),
                escrowId,
                price,
                tax,
                starts.minus(Duration.ofDays(3)),
                now,
                starts.minus(Duration.ofHours(24)),
                provider.merchantId());
        if ("signed_off".equals(state) || "completed".equals(state)) {
            int e = 0;
            for (var type : "signed_off".equals(state)
                    ? List.of("en_route", "on_site", "completed", "signed_off")
                    : List.of("en_route", "on_site", "completed")) {
                db.update(
                        """
                        insert into booking.booking_events (id, booking_id, type, at, actor_id)
                        values (?, ?, ?, ?, ?) on conflict (id) do update set at = excluded.at
                        """,
                        DemoIds.of("booking-event:" + key + ":" + e),
                        id,
                        type,
                        starts.plus(Duration.ofMinutes(e * 20L - 20)),
                        "signed_off".equals(type) ? customerId : provider.merchantId());
                e++;
            }
        }
        if (reviewed) {
            db.update(
                    """
                    insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, tags, text, lang, author_name,
                        job_label, created_at)
                    select ?, 'booking', ?, ?, 'merchant', ?, 5, '{on_time}', ?, 'en', u.first_name || ' ' || left(u.last_name, 1) || '.', ?, ?
                      from identity.users u where u.id = ?
                    on conflict do nothing
                    """,
                    DemoIds.of("review:" + key),
                    ref,
                    customerId,
                    provider.merchantId(),
                    "Great " + l.title().toLowerCase(java.util.Locale.ROOT) + ", right on time. Booking again.",
                    l.title(),
                    ends.plus(Duration.ofHours(30)),
                    customerId);
            count("reviews");
        }
        count(
                switch (state) {
                    case "signed_off", "completed" -> "past bookings";
                    default ->
                        starts.isBefore(now.plus(Duration.ofDays(1))) && day.equals(today)
                                ? "jobs today"
                                : "upcoming bookings";
                });
    }

    private void quoteRequest(Made provider, String customerId) {
        var b = provider.business();
        db.update(
                """
                insert into booking.quote_requests (id, customer_id, category_id, details, media, merchant_ids, expires_at, respond_by, created_at)
                values (?, ?, ?, ?::jsonb, '{}', ?, ?, ?, ?)
                on conflict (id) do update set expires_at = excluded.expires_at, respond_by = excluded.respond_by, created_at = excluded.created_at
                """,
                DemoIds.of("quote-request:" + b.key()),
                customerId,
                b.categories().getFirst(),
                DemoBusinesses.json(Map.of(
                        "title",
                        "Quote for " + b.listings().getFirst().title().toLowerCase(java.util.Locale.ROOT),
                        "area",
                        DemoMarket.QUARTERS.get(1),
                        "description",
                        "Can you come this week? Weekday mornings work best for me.")),
                Db.array(provider.merchantId()),
                now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofMinutes(75)),
                now.minus(Duration.ofMinutes(40)));
        count("open quote requests");
    }

    // ── money ───────────────────────────────────────────────────────────────────────────────────────────────────

    private String escrow(
            String key,
            String refType,
            String refId,
            Made merchant,
            long amount,
            String kind,
            String label,
            @Nullable String orderNumber,
            String customerId,
            @Nullable String listingId,
            Instant occurred,
            String state,
            @Nullable Instant released) {
        var id = DemoIds.of("escrow:" + key);
        var intent = DemoIds.of("intent:" + key);
        var tax = Math.round(amount * 0.05);
        db.update(
                """
                insert into payments.payment_intents (id, stripe_pi, customer_id, amount_cents, currency, capture_method, state, three_ds,
                    ref_type, ref_id, merchant_id, authorized_at, created_at)
                values (?, ?, ?, ?, 'CAD', 'manual', ?, false, ?, ?, ?, ?, ?) on conflict (id) do update set state = excluded.state
                """,
                intent,
                "pi_demo_" + intent.substring(6),
                customerId,
                amount + tax,
                "released".equals(state) ? "captured" : "authorized",
                refType,
                refId,
                merchant.merchantId(),
                occurred,
                occurred);
        var rate = "master".equals(merchant.business().tier())
                ? 900
                : "trusted".equals(merchant.business().tier()) ? 1200 : 1500;
        db.update(
                """
                insert into payments.escrows (id, payment_intent_id, ref_type, ref_id, merchant_id, amount_cents, release_at, released_at, state,
                    kind, label, order_number, customer_id, customer_name, listing_id, listing_name, source, take_rate_bps, fee_cents,
                    tax_cents, occurred_at, fulfilled_at, created_at)
                select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, u.first_name || ' ' || left(u.last_name, 1) || '.', ?, ?, 'search', ?, ?, ?, ?, ?, ?
                  from identity.users u where u.id = ?
                on conflict (id) do update set state = excluded.state, release_at = excluded.release_at, released_at = excluded.released_at,
                    occurred_at = excluded.occurred_at
                """,
                id,
                intent,
                refType,
                refId,
                merchant.merchantId(),
                amount,
                released == null ? occurred.plus(Duration.ofDays(2)) : released,
                released,
                state,
                kind,
                label,
                orderNumber,
                customerId,
                listingId,
                label,
                rate,
                amount * rate / 10_000,
                tax,
                occurred,
                released,
                occurred,
                customerId);
        return id;
    }

    /** Twelve weeks of released sales for one of the demo owner's businesses: the Studio's earnings chart. */
    private void earnings(Made m, String kind) {
        var listings = m.business().listings();
        for (int week = 1; week <= 12; week++) {
            var perWeek = 3 + Math.floorMod((m.business().key() + week).hashCode(), 5);
            for (int i = 0; i < perWeek; i++) {
                var at = now.minus(Duration.ofDays(week * 7L - i)).minus(Duration.ofHours(3L * i));
                long amount;
                String label;
                String listing = null;
                if (listings.isEmpty()) {
                    var dishes = m.business().menuSections().stream()
                            .flatMap(s -> s.items().stream())
                            .toList();
                    var d = dishes.get(Math.floorMod(week + i, dishes.size()));
                    amount = d.priceCents() * 2 + 499;
                    label = d.name();
                } else {
                    var idx = Math.floorMod(week + i, listings.size());
                    var p = listings.get(idx).priceCents();
                    amount = (p == null ? 10000 : p) * (kind.equals("goods") ? 2 : 1);
                    label = listings.get(idx).title();
                    listing = m.listingIds().get(idx);
                }
                var key = "history:" + m.business().key() + ":" + week + ":" + i;
                escrow(
                        key,
                        "goods".equals(kind) ? "order_line" : "food".equals(kind) ? "food_order" : "booking",
                        DemoIds.of("ref:" + key),
                        m,
                        amount,
                        kind,
                        label,
                        null,
                        DemoAccounts.reviewerId(Math.floorMod(key.hashCode(), 12)),
                        listing,
                        at,
                        "released",
                        at.plus(Duration.ofDays("service".equals(kind) ? 2 : "goods".equals(kind) ? 7 : 0)));
            }
        }
        count("weeks of earnings (owner)");
    }

    // ── the console's work ──────────────────────────────────────────────────────────────────────────────────────

    private void dispute(
            String key,
            Made provider,
            String customerId,
            String state,
            String subject,
            String statement,
            Instant opened) {
        var l = provider.business().listings().getFirst();
        var price = l.priceCents() == null ? 10000 : l.priceCents();
        var escrow = escrow(
                "dispute:" + key,
                "booking",
                DemoIds.of("booking:dispute:" + key),
                provider,
                price,
                "service",
                subject,
                null,
                customerId,
                provider.listingIds().getFirst(),
                opened.minus(Duration.ofDays(3)),
                "disputed",
                null);
        db.update(
                """
                insert into payments.disputes (id, ref_type, ref_id, opened_by, evidence, state, merchant_id, case_number, subject, amount_cents,
                    customer_name, customer_statement, respond_by, opened_at)
                select ?, 'escrow', ?, ?, '[]'::jsonb, ?, ?, ?, ?, ?, u.first_name || ' ' || left(u.last_name, 1) || '.', ?, ?, ?
                  from identity.users u where u.id = ?
                on conflict (id) do update set state = excluded.state, respond_by = excluded.respond_by, opened_at = excluded.opened_at
                """,
                DemoIds.of("dispute:" + key),
                escrow,
                customerId,
                state,
                provider.merchantId(),
                "DS-D" + escrow.substring(22),
                subject,
                price,
                statement,
                opened.plus(Duration.ofDays(3)),
                opened,
                customerId);
        count("open disputes");
    }

    /** A pending listing the automated vetting flagged (a price far from its category's): the console's vetting queue. */
    private void flag(Made seller) {
        var id = seller.listingIds().getLast();
        db.update("""
                update catalogue.offers set vetting = 'pending', status = 'hidden', vetting_flags = '{price_outlier}',
                    submitted_at = ?, price_cents = price_cents * 6 where id = ? and vetting <> 'pending'
                """, now.minus(Duration.ofHours(3)), id);
        count("flagged listings");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static @Nullable Made find(List<Made> made, String key) {
        return made.stream()
                .filter(m -> m.business().key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private static String reviewer(int i, int reviewers) {
        return DemoAccounts.reviewerId(Math.floorMod(i, reviewers));
    }

    private void count(String what) {
        counts.merge(what, 1, Integer::sum);
    }
}
