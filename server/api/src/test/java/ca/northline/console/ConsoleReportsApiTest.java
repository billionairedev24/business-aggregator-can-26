package ca.northline.console;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-95: reports from aggregates — weekly active customers, the shop funnel, signup-month cohorts, top categories and
 * waitlist demand for a province (New Brunswick: no other test sells there), with small counts withheld and no personal data in
 * the answer; admin, finance and analysts only.
 */
class ConsoleReportsApiTest extends IntegrationTest {

    static final String URL = "/api/v1/console/reports";
    static final ZoneId NEW_BRUNSWICK = ZoneId.of("America/Moncton");

    @Autowired
    JdbcClient jdbc;

    static String staff = "";
    static String shop = "";
    static String category = "";
    static List<String> customers = List.of();

    @BeforeEach
    void yukon() {
        if (!shop.isEmpty()) {
            return;
        }
        // earlier runs' New Brunswick test businesses leave the province, so the counts below are this run's
        jdbc.sql("update merchants.merchants set province = null where province = 'NB' and display_name like 'S95 %'")
                .update();
        staff = data.user("Ana Analyst");
        shop = data.merchant("both", "S95 Moncton Garage " + Ids.next().substring(20));
        jdbc.sql("update merchants.merchants set province = 'NB' where id = ?")
                .params(shop)
                .update();
        category = "service.s95-" + Ids.next().substring(16).toLowerCase(java.util.Locale.ROOT);
        jdbc.sql(
                        "insert into catalogue.categories (id, root, name_i18n) values (?, 'service', '{\"en\":\"Snow removal\",\"fr\":\"Déneigement\"}'::jsonb)")
                .params(category)
                .update();
        var service = Ids.next();
        jdbc.sql(
                        "insert into catalogue.services (id, merchant_id, category_id, pricing_mode, price_cents, status) values (?, ?, ?, 'fixed', 9000, 'live')")
                .params(service, shop, category)
                .update();

        var lastMonth = YearMonth.now(NEW_BRUNSWICK).minusMonths(1);
        var signup = lastMonth.atDay(2).atTime(12, 0).atZone(NEW_BRUNSWICK).toInstant();
        var now = Instant.now();
        var people = new ArrayList<String>();
        for (var i = 0; i < 6; i++) {
            var customer = data.user("Customer " + i);
            jdbc.sql("update identity.users set created_at = ? where id = ?")
                    .params(JdbcTimes.ts(signup), customer)
                    .update();
            people.add(customer);
            order(customer, signup.plus(Duration.ofHours(1)));
            order(customer, now.minus(Duration.ofMinutes(2)));
        }
        customers = List.copyOf(people);
        booking(customers.getFirst(), service, now.minus(Duration.ofMinutes(3)));
        jdbc.sql("""
                        insert into merchants.storefront_visits (merchant_id, day, visits) values (?, ?, 120)
                        on conflict (merchant_id, day) do update set visits = 120""").params(shop, LocalDate.now(NEW_BRUNSWICK)).update();
    }

    void order(String customer, Instant at) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, subtotal_cents, delivery_fee_cents,
                               service_fee_cents, tax_cents, tip_cents, delivery_kind, placed_at)
                        values (?, ?, ?, 'goods', 'delivered', 2000, 0, 0, 0, 0, 'pooled', ?)""")
                .params(id, "NL-9" + id.substring(19), customer, JdbcTimes.ts(at))
                .update();
        jdbc.sql(
                        "insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents, state, title) values (?, ?, ?, 1, 2000, 'pending', 'Chains')")
                .params(Ids.next(), id, shop)
                .update();
    }

    void booking(String customer, String service, Instant at) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.bookings (id, ref, customer_id, merchant_id, service_id, type, state, starts_at, ends_at,
                               title, price_cents, tax_cents, deposit_cents, source, created_at)
                        values (?, ?, ?, ?, ?, 'visit', 'confirmed', ?, ?, 'Driveway', 9000, 0, 0, 'customer', ?)""")
                .params(
                        id,
                        "BK-9" + id.substring(20),
                        customer,
                        shop,
                        service,
                        JdbcTimes.ts(at.plus(Duration.ofDays(2))),
                        JdbcTimes.ts(at.plus(Duration.ofDays(2)).plus(Duration.ofHours(1))),
                        JdbcTimes.ts(at))
                .update();
    }

    @Test
    void aProvincesHealth_fromCountsOnly() throws Exception {
        var lastMonth = YearMonth.now(NEW_BRUNSWICK).minusMonths(1).toString();
        mvc.perform(get(URL).param("province", "NB").with(TestJwt.staff(staff, StaffRole.ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.province").value("NB"))
                .andExpect(jsonPath("$.weeks.length()").value(13))
                .andExpect(jsonPath("$.weeks[12].customers").value(6))
                .andExpect(jsonPath("$.funnel[0].step").value("app_opens"))
                .andExpect(jsonPath("$.funnel[0].recorded").value(false))
                .andExpect(jsonPath("$.funnel[1].count").value(120))
                .andExpect(jsonPath("$.funnel[2].recorded").value(false))
                .andExpect(jsonPath("$.cohorts.length()").value(4))
                .andExpect(jsonPath("$.cohorts[3].month").value(lastMonth))
                .andExpect(jsonPath("$.cohorts[3].customers").value(6))
                .andExpect(jsonPath("$.cohorts[3].m1").value(100.0))
                .andExpect(jsonPath("$.cohorts[3].m2").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.topCategories[0].categoryId").value(category))
                .andExpect(jsonPath("$.topCategories[0].names.fr").value("Déneigement"))
                .andExpect(jsonPath("$.topCategories[0].salesCents").value(9000))
                // aggregates only: no customer id anywhere in the answer
                .andExpect(jsonPath("$..customerId").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(Matchers.not(Matchers.containsString(customers.getFirst()))));
    }

    @Test
    void smallCountsAreWithheld() throws Exception {
        // two of the six bought again a week ago: 2 is under the minimum cell, so it comes back null, not 2
        var weekAgo = Instant.now().minus(Duration.ofDays(7));
        order(customers.get(0), weekAgo);
        order(customers.get(1), weekAgo);
        mvc.perform(get(URL).param("province", "NB").with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks[11].customers").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.weeks[12].customers").value(6));
    }

    @Test
    void rolesAndPlaces() throws Exception {
        mvc.perform(get(URL).with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel[2].recorded").value(true));
        mvc.perform(get(URL).param("province", "ZZ").with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a province from the list."));
        for (var role :
                List.of(StaffRole.TRUST_SAFETY, StaffRole.DISPATCH, StaffRole.SUPPORT, StaffRole.SUPPORT_LEAD)) {
            mvc.perform(get(URL).with(TestJwt.staff(staff, role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL).with(TestJwt.staffWithoutMfa(staff, StaffRole.ANALYST)))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL).with(TestJwt.customer(staff))).andExpect(status().isForbidden());
    }
}
