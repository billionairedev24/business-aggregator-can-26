package ca.northline.console;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-81: the orders monitor — orders and bookings side by side with what needs attention (issue, late, stuck run,
 * escrow held past 48 h), filtered by the region model. Rows live in a province no other test trades in (NT); the
 * shared database is never wiped, so assertions look for this test's references.
 */
class ConsoleOrdersMonitorApiTest extends IntegrationTest {

    static final String PROVINCE = "NT";

    @Autowired
    JdbcClient jdbc;

    String staff;
    String prefix;
    String seller;
    String provider;

    @BeforeEach
    void marketplace() {
        staff = data.user("Mo Monitor");
        prefix = "M" + Ids.next().substring(18).toUpperCase(Locale.ROOT);
        seller = business("seller", "S81 Bakery");
        provider = business("provider", "S81 Mechanics");
        var now = Instant.now();
        var customer = data.user("Amara Osei");
        // an order on a pooled run whose window ended an hour ago, still being packed → late
        order(
                "late",
                "packing",
                window(now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1))),
                customer,
                null);
        // a delivered order with a reported line issue → issue
        order("issue", "delivered", null, customer, "Wrong size");
        // a delivered order without trouble
        order("fine", "delivered", null, customer, null);
        // an order on its way whose run has a stop 30 min past its ETA → stuck
        var stuck = order("stuck", "picked_up", null, customer, null);
        stuckRun(stuck, now.minus(Duration.ofMinutes(30)));
        // a booking whose provider hasn't started 1 h after the start → late
        booking("blate", "confirmed", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), customer);
        // a completed job, escrow held 3 days after it ended → escrow > 48 h
        booking(
                "bheld",
                "completed",
                now.minus(Duration.ofDays(3)).minus(Duration.ofHours(2)),
                now.minus(Duration.ofDays(3)),
                customer);
        // a request waiting for the provider
        booking(
                "bnew",
                "requested",
                now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1)).plus(Duration.ofHours(2)),
                customer);
    }

    @Test
    void needsAttentionListsIssuesLateStuckAndLongEscrowFirst() throws Exception {
        mvc.perform(get("/api/v1/console/orders")
                        .param("province", PROVINCE)
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath(
                        "$.items[*].ref",
                        containsInAnyOrder(ref("late"), ref("issue"), ref("stuck"), ref("blate"), ref("bheld"))))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("late")))
                        .value("late"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("issue")))
                        .value("issue"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("stuck")))
                        .value("stuck"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("blate")))
                        .value("late"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("bheld")))
                        .value("escrow_48h"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].customer".formatted(ref("late")))
                        .value("A. Osei"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].sellers[0]".formatted(ref("late")))
                        .value("S81 Bakery"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].amountCents".formatted(ref("late")))
                        .value(2_500))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].type".formatted(ref("bheld")))
                        .value("service"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].amountCents".formatted(ref("bheld")))
                        .value(9_345))
                .andExpect(jsonPath("$.counts.attention").value(5))
                .andExpect(jsonPath("$.counts.live").value(4))
                .andExpect(jsonPath("$.counts.escrow").value(1))
                .andExpect(jsonPath("$.counts.late").value(3))
                .andExpect(jsonPath("$.counts.all").value(7));
    }

    @Test
    void theOtherViewsAndEveryRoleThatOpensTheScreen() throws Exception {
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "all")
                        .param("province", PROVINCE)
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(7)))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("fine")))
                        .value("delivered"))
                .andExpect(jsonPath("$.items[?(@.ref == '%s')].status".formatted(ref("bnew")))
                        .value("new"));
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "late")
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].ref", containsInAnyOrder(ref("late"), ref("stuck"), ref("blate"))));
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "escrow")
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].ref", containsInAnyOrder(ref("bheld"))));
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "live")
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].ref", not(hasItem(ref("fine")))))
                .andExpect(jsonPath("$.items[*].ref", hasItem(ref("bnew"))));
    }

    @Test
    void theRegionFilterLeavesOtherProvincesOut() throws Exception {
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "all")
                        .param("province", "YT")
                        .param("q", prefix)
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void rolesThatDontOpenOrdersAndUnverifiedSignInsAreRefused() throws Exception {
        for (var role : new StaffRole[] {StaffRole.FINANCE, StaffRole.ANALYST, StaffRole.TRUST_SAFETY}) {
            mvc.perform(get("/api/v1/console/orders").with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        mvc.perform(get("/api/v1/console/orders").with(TestJwt.staffWithoutMfa(staff, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get("/api/v1/console/orders").with(TestJwt.customer(staff))).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownViewOrPlaceIsA422() throws Exception {
        mvc.perform(get("/api/v1/console/orders").param("view", "stuck").with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("view"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose needs attention, live, escrow, late or all."));
        mvc.perform(get("/api/v1/console/orders")
                        .param("province", "ZZ")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province from the list."));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────

    private String ref(String name) {
        return prefix + "-" + name.toUpperCase(Locale.ROOT);
    }

    private String business(String type, String name) {
        var id = data.merchant(type, name);
        jdbc.sql("update merchants.merchants set province = :p, city = 'Yellowknife' where id = :id")
                .param("p", PROVINCE)
                .param("id", id)
                .update();
        return id;
    }

    private String window(Instant starts, Instant ends) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.delivery_windows (id, zone_id, starts_at, ends_at, cutoff_at, capacity)
                        values (:id, 'test-zone', :s, :e, :s, 10)""")
                .param("id", id)
                .param("s", JdbcTimes.ts(starts))
                .param("e", JdbcTimes.ts(ends))
                .update();
        return id;
    }

    private String order(String name, String state, @Nullable String window, String customer, @Nullable String issue) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, window_id, subtotal_cents,
                                                   delivery_fee_cents, placed_at, delivered_at)
                        values (:id, :ref, :c, 'goods', :s, :w, 2000, 500, :p, :d)""")
                .param("id", id)
                .param("ref", ref(name))
                .param("c", customer)
                .param("s", state)
                .param("w", window)
                .param("p", JdbcTimes.ts(Instant.now().minus(Duration.ofHours(5))))
                .param(
                        "d",
                        JdbcTimes.ts(state.equals("delivered") ? Instant.now().minus(Duration.ofHours(1)) : null))
                .update();
        jdbc.sql("""
                        insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, issue_note)
                        values (:id, :o, :m, 2, 1000, 'packed', :issue)""")
                .param("id", Ids.next())
                .param("o", id)
                .param("m", seller)
                .param("issue", issue)
                .update();
        return id;
    }

    private void stuckRun(String orderId, Instant eta) {
        var run = Ids.next();
        jdbc.sql("insert into fulfilment.runs (id, kind, state, market) values (:id, 'direct', 'en_route', 'S81town')")
                .param("id", run)
                .update();
        jdbc.sql("""
                        insert into fulfilment.deliveries (order_id, order_type, kind, market, pin, state, run_id)
                        values (:o, 'goods', 'direct', 'S81town', '1234', 'picked_up', :r)""").param("o", orderId).param("r", run).update();
        jdbc.sql("""
                        insert into fulfilment.stops (id, run_id, order_id, kind, seq, eta, state)
                        values (:id, :r, :o, 'dropoff', 1, :eta, 'pending')""")
                .param("id", Ids.next())
                .param("r", run)
                .param("o", orderId)
                .param("eta", JdbcTimes.ts(eta))
                .update();
    }

    private void booking(String name, String state, Instant starts, Instant ends, String customer) {
        jdbc.sql("""
                        insert into booking.bookings (id, ref, customer_id, merchant_id, type, state, starts_at, ends_at,
                                                      price_cents, created_at)
                        values (:id, :ref, :c, :m, 'home', :s, :st, :en, 9345, :at)""")
                .param("id", Ids.next())
                .param("ref", ref(name))
                .param("c", customer)
                .param("m", provider)
                .param("s", state)
                .param("st", JdbcTimes.ts(starts))
                .param("en", JdbcTimes.ts(ends))
                .param("at", JdbcTimes.ts(Instant.now().minus(Duration.ofDays(4))))
                .update();
    }
}
