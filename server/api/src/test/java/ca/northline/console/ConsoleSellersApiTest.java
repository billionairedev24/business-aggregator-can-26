package ca.northline.console;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-82: the sellers directory and seller detail composed from merchants, trust, orders, booking and payments. Rows
 * carry a unique name prefix (the shared database is never wiped); place filters use a province no other console test
 * trades in (NU).
 */
class ConsoleSellersApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String staff;
    String prefix;
    String bow;
    String glen;

    @BeforeEach
    void sellers() {
        staff = data.user("Dev Kaur");
        prefix = "S82" + Ids.next().substring(20).toLowerCase(Locale.ROOT);
        bow = business("provider", prefix + " Bow River Mechanics", "trusted");
        glen = business("seller", prefix + " Glenmore Bakery", "master");
        // Bow River: quality 78 (< Trusted floor 80), insurance expiring in 21 days, $240 + $100 of jobs, one dispute
        quality(bow, 78);
        new SettingsFixtures(jdbc).verification(bow, "insurance", null, null, "verified",
                Instant.now().plus(Duration.ofDays(21)).plus(Duration.ofHours(2)), null);
        new SettingsFixtures(jdbc).verification(bow, "licence", "AMVIC", "51022", "verified", null, null);
        booking(bow, 24_000);
        booking(bow, 10_000);
        dispute(bow);
        // Glenmore: quality 94, $41.20 of goods, an open off-platform payment flag
        quality(glen, 94);
        order(glen, 2, 2_060);
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, state, merchant_id)
                        values (?, 'merchant', ?, 'off_platform_payment', 'open', ?)""")
                .params(Ids.next(), glen, glen)
                .update();
    }

    @Test
    void theDirectoryShowsTierQualityGmvDisputesAndWhatPutsABusinessAtRisk() throws Exception {
        mvc.perform(get("/api/v1/console/sellers").param("q", prefix).param("province", "NU")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.active").value(2))
                .andExpect(jsonPath("$.atRisk").value(2))
                .andExpect(jsonPath("$.items[0].name").value(prefix + " Bow River Mechanics"))
                .andExpect(jsonPath("$.items[0].type").value("provider"))
                .andExpect(jsonPath("$.items[0].province").value("NU"))
                .andExpect(jsonPath("$.items[0].tier").value("trusted"))
                .andExpect(jsonPath("$.items[0].quality").value(78))
                .andExpect(jsonPath("$.items[0].gmv90Cents").value(34_000))
                .andExpect(jsonPath("$.items[0].disputeRate").value(0.5))
                .andExpect(jsonPath("$.items[0].flags[0].kind").value("quality_below"))
                .andExpect(jsonPath("$.items[0].flags[0].floor").value(80.0))
                .andExpect(jsonPath("$.items[0].flags[1].kind").value("disputes_above"))
                .andExpect(jsonPath("$.items[0].flags[2].kind").value("check_expiring"))
                .andExpect(jsonPath("$.items[0].flags[2].checkType").value("insurance"))
                .andExpect(jsonPath("$.items[0].flags[2].days").value(21))
                .andExpect(jsonPath("$.items[1].gmv90Cents").value(4_120))
                .andExpect(jsonPath("$.items[1].disputeRate").value(0.0))
                .andExpect(jsonPath("$.items[1].flags[0].kind").value("trust_flag"))
                .andExpect(jsonPath("$.items[1].flags[0].rule").value("off_platform_payment"));
        // support opens the directory too
        mvc.perform(get("/api/v1/console/sellers").param("q", prefix).with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/api/v1/console/sellers").param("q", prefix).param("province", "YT")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void theDetailHasKpisAgainstFloorsChecksAndTheOversightTrail() throws Exception {
        mvc.perform(post("/api/v1/console/merchants/{id}/tier", bow)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"registered\",\"reason\":\"Quality below the floor\"}")
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/console/sellers/{id}", bow).with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.tier").value("registered"))
                .andExpect(jsonPath("$.quality.value").value(78.0))
                .andExpect(jsonPath("$.gmv90Cents").doesNotExist())
                .andExpect(jsonPath("$.seller.gmv90Cents").value(34_000))
                .andExpect(jsonPath("$.disputes.value").value(50.0))
                .andExpect(jsonPath("$.ratingCount").value(0))
                .andExpect(jsonPath("$.checks", hasSize(2)))
                .andExpect(jsonPath("$.checks[?(@.checkType == 'licence')].reference").value("51022"))
                .andExpect(jsonPath("$.trail", hasSize(1)))
                .andExpect(jsonPath("$.trail[0].action").value("tier_changed"))
                .andExpect(jsonPath("$.trail[0].reason").value("Quality below the floor"))
                .andExpect(jsonPath("$.trail[0].detail.from").value("trusted"))
                .andExpect(jsonPath("$.trail[0].actorName").value("Dev Kaur"));
        mvc.perform(get("/api/v1/console/sellers/{id}", Ids.next()).with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rolesThatDontOpenSellersAreRefused() throws Exception {
        for (var role : new StaffRole[] {StaffRole.DISPATCH, StaffRole.FINANCE, StaffRole.ANALYST}) {
            mvc.perform(get("/api/v1/console/sellers").with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/console/sellers/{id}", bow).with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/console/sellers").with(TestJwt.staffWithoutMfa(staff, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get("/api/v1/console/sellers").param("market", "mkt-nowhere").with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a market from the list."));
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────────────

    private String business(String type, String name, String tier) {
        var id = data.merchant(type, name);
        jdbc.sql("update merchants.merchants set province = 'NU', city = 'Iqaluit', tier = ?, status = 'active' where id = ?")
                .params(tier, id)
                .update();
        return id;
    }

    private void quality(String merchant, int score) {
        jdbc.sql("insert into trust.quality_scores (merchant_id, date, score, components) values (?, ?, ?, '[]'::jsonb)")
                .params(merchant, LocalDate.now(), score)
                .update();
    }

    private void booking(String merchant, long price) {
        jdbc.sql("""
                        insert into booking.bookings (id, customer_id, merchant_id, type, state, starts_at, price_cents, created_at)
                        values (:id, :c, :m, 'home', 'signed_off', :at, :p, :at)""")
                .param("id", Ids.next())
                .param("c", Ids.next())
                .param("m", merchant)
                .param("p", price)
                .param("at", JdbcTimes.ts(Instant.now().minus(Duration.ofDays(10))))
                .update();
    }

    private void order(String merchant, int qty, long unit) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, subtotal_cents, placed_at)
                        values (:id, :c, 'goods', 'confirmed', :s, :at)""")
                .param("id", id)
                .param("c", Ids.next())
                .param("s", qty * unit)
                .param("at", JdbcTimes.ts(Instant.now().minus(Duration.ofDays(5))))
                .update();
        jdbc.sql("""
                        insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state)
                        values (:id, :o, :m, :q, :u, 'packed')""")
                .param("id", Ids.next())
                .param("o", id)
                .param("m", merchant)
                .param("q", qty)
                .param("u", unit)
                .update();
    }

    private void dispute(String merchant) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.disputes (id, ref_type, state, merchant_id, case_number, opened_at)
                        values (:id, 'escrow', 'agent', :m, :n, :at)""")
                .param("id", id)
                .param("n", "DS-" + id)
                .param("m", merchant)
                .param("at", JdbcTimes.ts(Instant.now().minus(Duration.ofDays(3))))
                .update();
    }
}
