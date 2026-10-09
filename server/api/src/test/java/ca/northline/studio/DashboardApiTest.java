package ca.northline.studio;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestJwt;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code GET /api/v1/merchants/{id}/dashboard} — composed from booking, orders, payments, trust, merchants. */
class DashboardApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void composesTodaysJobsQuotesComplianceAndCoaching() throws Exception {
        var zone = ZoneId.of("America/Edmonton");
        var fx = new OperationsFixtures(jdbc);
        var biz = data.business(MerchantRole.OWNER);
        var jas = data.user("Jas Gill");
        data.member(biz.merchantId(), jas, MerchantRole.TECHNICIAN);
        var customer = data.user("Amara Osei");
        var today = LocalDate.now(zone);
        var nine = fx.job(
                biz.merchantId(),
                biz.userId(),
                customer,
                today.atTime(9, 0).atZone(zone).toInstant(),
                "confirmed");
        // what the customer paid and is held: the payments ledger's sale + GST/HST, not the price before tax
        fx.escrow(biz.merchantId(), nine, 30_000, 1_500, 900, "held");
        fx.job(biz.merchantId(), jas, customer, today.atTime(14, 0).atZone(zone).toInstant(), "confirmed");
        fx.job(
                biz.merchantId(),
                biz.userId(),
                customer,
                today.minusDays(1).atStartOfDay(zone).toInstant(),
                "completed");
        fx.quoteRequest(biz.merchantId(), customer);
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_type, status, expires_at)
                        values (?, ?, 'wcb', 'expired', now() - interval '8 days')
                        """).params(Ids.next(), biz.merchantId()).update();

        mvc.perform(get("/api/v1/merchants/{m}/dashboard", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.jobsToday", hasSize(2)))
                .andExpect(jsonPath("$.jobsToday[0].customerName").value("A. Osei"))
                .andExpect(jsonPath("$.jobsToday[0].escrowHeldCents").value(31_500))
                .andExpect(jsonPath("$.jobsToday[1].escrowHeldCents").value(9345))
                .andExpect(jsonPath("$.jobsToday[0].mine").value(true))
                .andExpect(jsonPath("$.jobsToday[1].mine").value(false))
                .andExpect(jsonPath("$.jobsToday[1].memberName").value("Jas"))
                .andExpect(jsonPath("$.counts.visitsToday").value(2))
                .andExpect(jsonPath("$.counts.quoteRequestsOpen").value(1))
                .andExpect(jsonPath("$.compliance[0].checkType").value("wcb"))
                .andExpect(jsonPath("$.compliance[0].pausesAt").exists())
                .andExpect(jsonPath("$.coaching.jobs").value(1))
                .andExpect(jsonPath("$.coaching.withoutPhotos").value(1))
                .andExpect(jsonPath("$.earnings.weeks", hasSize(12)));
    }

    @Test
    void membersOnlyWithMfa() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{m}/dashboard", biz.merchantId()).with(TestJwt.member(data.user("X"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/merchants/{m}/dashboard", biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden());
    }
}
