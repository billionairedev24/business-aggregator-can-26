package ca.northline.console;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-91: the console overview composed from the owning modules' query APIs, filtered by the region model. The figures
 * are asserted for one province no other test trades in (Yukon): the database is shared and never wiped, so
 * platform-wide totals can't be asserted — only their shape.
 */
class ConsoleOverviewApiTest extends IntegrationTest {

    static final String PROVINCE = "YT";

    @Autowired
    JdbcClient jdbc;

    String seller;
    String provider;
    String applicant;

    @BeforeEach
    void yukonMarketplace() {
        // Fresh businesses each test (never wiped): figures below are per-test deltas over what earlier tests left.
        jdbc.sql("delete from merchants.merchants where province = :p and display_name like 'S91 %'")
                .param("p", PROVINCE)
                .update();
        seller = business("seller", "active", null);
        provider = business("provider", "active", null);
        applicant = business("provider", "pending", Instant.now().minus(Duration.ofDays(3)));
        var now = Instant.now();
        var lastWeek = now.minus(Duration.ofDays(2));
        var twoWeeksAgo = now.minus(Duration.ofDays(9));
        // goods: one order this week (2 × $10 + a refunded line), one the week before ($5), one cancelled
        var window = window(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));
        order(
                lastWeek,
                "delivered",
                window,
                now.minus(Duration.ofDays(2)).minusSeconds(60),
                399,
                2,
                1000,
                seller,
                true);
        order(twoWeeksAgo, "confirmed", null, null, 0, 1, 500, seller, false);
        order(lastWeek, "cancelled", null, null, 0, 1, 9_900, seller, false);
        // services: a $120 job booked this week, one on site right now, one cancelled
        booking(lastWeek, "confirmed", 12_000);
        booking(lastWeek, "on_site", 8_000);
        booking(lastWeek, "cancelled", 50_000);
        // money: $15 revenue at release, $3 given back on a dispute, $200 held, one dispute waiting for an agent
        var escrow = escrow("released", 10_000);
        var held = escrow("held", 20_000);
        ledger(escrow, "escrow", 1_500, 0, lastWeek);
        var dispute = dispute(held, "agent", now.minus(Duration.ofDays(1)));
        ledger(dispute, "dispute", 0, 300, lastWeek);
        // trust: an off-platform payment flag, one business below the floor
        flag("off_platform_payment");
        quality(provider, 72);
        quality(seller, 91);
        // catalogue: a flagged listing waiting
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, vetting, vetting_flags, submitted_at)
                        values (:id, :m, 'pending', '{missing_licence}', :at)""")
                .param("id", Ids.next())
                .param("m", provider)
                .param("at", JdbcTimes.ts(now.minus(Duration.ofHours(2))))
                .update();
    }

    @Test
    void anAnalyst_seesTheProvincesFigures() throws Exception {
        mvc.perform(get("/api/v1/console/overview")
                        .param("province", "yt")
                        .with(TestJwt.staff(data.user("Ana"), StaffRole.ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.province").value(PROVINCE))
                .andExpect(jsonPath("$.timeZone").value("America/Whitehorse"))
                .andExpect(jsonPath("$.headline.gmvCents").value(2_000 + 12_000 + 8_000))
                .andExpect(jsonPath("$.headline.sellers").value(2))
                .andExpect(jsonPath("$.headline.verifications").value(1))
                .andExpect(jsonPath("$.headline.disputes").value(1))
                .andExpect(jsonPath("$.kpis.gmvCents").value(22_000))
                .andExpect(jsonPath("$.kpis.previousGmvCents").value(500))
                .andExpect(jsonPath("$.kpis.revenueCents").value(1_200))
                .andExpect(jsonPath("$.kpis.orders").value(1))
                .andExpect(jsonPath("$.kpis.bookings").value(2))
                .andExpect(jsonPath("$.kpis.onTimeRatio").value(1.0))
                .andExpect(jsonPath("$.kpis.disputeRate").value(closeTo(1.0 / 3, 1e-9)))
                .andExpect(jsonPath("$.kpis.averageDeliveryFeeCents").value(399))
                .andExpect(jsonPath("$.weeks", hasSize(12)))
                .andExpect(jsonPath("$.weeks[11].goodsCents").value(2_000))
                .andExpect(jsonPath("$.weeks[11].servicesCents").value(20_000))
                .andExpect(jsonPath("$.weeks[10].goodsCents").value(500))
                .andExpect(jsonPath("$.workQueue.verifications.count").value(1))
                .andExpect(jsonPath("$.workQueue.flaggedListings.count").value(1))
                .andExpect(jsonPath("$.workQueue.disputes.count").value(1))
                .andExpect(jsonPath("$.workQueue.trustFlags.count").value(1))
                .andExpect(jsonPath("$.workQueue.trustFlags.offPlatformPayment").value(true))
                .andExpect(jsonPath("$.workQueue.sellersBelowFloor").value(1))
                .andExpect(jsonPath("$.live.providersOnJobs").value(1))
                .andExpect(jsonPath("$.live.escrowHeldCents").value(20_000))
                .andExpect(jsonPath("$.live.pools").isEmpty())
                .andExpect(jsonPath("$.health", hasSize(6)))
                .andExpect(jsonPath("$.health[0].key").value("api_p95"))
                .andExpect(jsonPath("$.health[0].status").value("unknown"))
                .andExpect(jsonPath("$.health[5].key").value("courier_app"));
    }

    @Test
    void theWholePlatform_hasTheSameShape() throws Exception {
        mvc.perform(get("/api/v1/console/overview").with(TestJwt.staff(data.user("Root"), StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.province").doesNotExist())
                .andExpect(jsonPath("$.weeks", hasSize(12)))
                .andExpect(jsonPath("$.workQueue.stuckRuns.count").isNumber())
                .andExpect(jsonPath("$.live.couriersOnRuns").isNumber());
    }

    @Test
    void unknownPlaces_are422() throws Exception {
        var staff = TestJwt.staff(data.user("Fin"), StaffRole.FINANCE);
        mvc.perform(get("/api/v1/console/overview").param("province", "ZZ").with(staff))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("province"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province from the list."));
        mvc.perform(get("/api/v1/console/overview").param("market", "nowhere").with(staff))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("market"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a market from the list."));
    }

    @Test
    void staffWithoutAConsoleRole_orWithoutMfa_areRefused() throws Exception {
        mvc.perform(get("/api/v1/console/overview").with(TestJwt.staff(data.user("New"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get("/api/v1/console/overview").with(TestJwt.staffWithoutMfa(data.user("Sam"), StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get("/api/v1/console/overview").with(TestJwt.member(data.user("Ravi"))))
                .andExpect(status().isForbidden());
    }

    // ── fixtures (tests may write any schema; the app reads through the modules' query APIs) ─────────────────────

    private String business(String type, String status, @Nullable Instant submittedAt) {
        var id = data.merchant(type, "S91 " + type);
        jdbc.sql("""
                        update merchants.merchants set province = :p, city = 'Whitehorse', status = :s, submitted_at = :at
                         where id = :id""")
                .param("p", PROVINCE)
                .param("s", status)
                .param("at", JdbcTimes.ts(submittedAt))
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

    private void order(
            Instant placed,
            String state,
            @Nullable String window,
            @Nullable Instant delivered,
            long fee,
            int qty,
            long unit,
            String merchant,
            boolean refundedExtra) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, window_id, subtotal_cents, delivery_fee_cents,
                                                   placed_at, delivered_at)
                        values (:id, :c, 'goods', :s, :w, :sub, :fee, :p, :d)""")
                .param("id", id)
                .param("c", Ids.next())
                .param("s", state)
                .param("w", window)
                .param("sub", qty * unit)
                .param("fee", fee)
                .param("p", JdbcTimes.ts(placed))
                .param("d", JdbcTimes.ts(delivered))
                .update();
        line(id, merchant, qty, unit, "packed");
        if (refundedExtra) {
            line(id, merchant, 1, 7_700, "refunded");
        }
    }

    private void line(String order, String merchant, int qty, long unit, String state) {
        jdbc.sql("""
                        insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state)
                        values (:id, :o, :m, :q, :u, :s)""")
                .param("id", Ids.next())
                .param("o", order)
                .param("m", merchant)
                .param("q", qty)
                .param("u", unit)
                .param("s", state)
                .update();
    }

    private void booking(Instant created, String state, long price) {
        jdbc.sql("""
                        insert into booking.bookings (id, customer_id, merchant_id, type, state, starts_at, price_cents, created_at)
                        values (:id, :c, :m, 'home', :s, :at, :p, :at)""")
                .param("id", Ids.next())
                .param("c", Ids.next())
                .param("m", provider)
                .param("s", state)
                .param("p", price)
                .param("at", JdbcTimes.ts(created))
                .update();
    }

    private String escrow(String state, long amount) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.escrows (id, ref_type, ref_id, merchant_id, amount_cents, state)
                        values (:id, 'booking', :ref, :m, :a, :s)""")
                .param("id", id)
                .param("ref", Ids.next())
                .param("m", provider)
                .param("a", amount)
                .param("s", state)
                .update();
        return id;
    }

    private void ledger(String ref, String refType, long credit, long debit, Instant at) {
        jdbc.sql("""
                        insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
                        values (:id, 'revenue', :d, :c, :t, :r, :at)""")
                .param("id", Ids.next())
                .param("d", debit)
                .param("c", credit)
                .param("t", refType)
                .param("r", ref)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    private String dispute(String escrow, String state, Instant opened) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.disputes (id, ref_type, ref_id, state, merchant_id, case_number, opened_at)
                        values (:id, 'escrow', :ref, :s, :m, :n, :at)""")
                .param("id", id)
                .param("ref", escrow)
                .param("s", state)
                .param("m", provider)
                .param("n", "DS-" + id)
                .param("at", JdbcTimes.ts(opened))
                .update();
        return id;
    }

    private void flag(String rule) {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, state, merchant_id)
                        values (:id, 'merchant', :m, :r, 'open', :m)""")
                .param("id", Ids.next())
                .param("m", seller)
                .param("r", rule)
                .update();
    }

    private void quality(String merchant, int score) {
        jdbc.sql("""
                        insert into trust.quality_scores (merchant_id, date, score, components)
                        values (:m, current_date, :s, '[]'::jsonb)""").param("m", merchant).param("s", score).update();
    }
}
