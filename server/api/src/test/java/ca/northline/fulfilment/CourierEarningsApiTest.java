package ca.northline.fulfilment;

import static ca.northline.support.CourierRuns.body;
import static ca.northline.support.CourierRuns.json;
import static ca.northline.support.CourierRuns.until;
import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.CourierRuns;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-151 courier earnings in market "Payville": a finished run is paid under the market's pay model (region
 * configuration over the platform defaults), tips count on the run that delivered them, the courier app shows today,
 * this week and each run, and the weekly run pays the courier through their Connect account (the fake gateway).
 */
@Import(MovableClock.Config.class)
class CourierEarningsApiTest extends IntegrationTest {

    static final String MARKET = "Payville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    PaymentsJobs jobs;

    ShopOrderFlow flow;
    CourierRuns runs;
    String bakery;
    String butcher;
    String baker;
    String butcherOwner;
    Listing bread;
    Listing steak;

    @BeforeEach
    void market() {
        clock.reset();
        runs = new CourierRuns(mvc, jdbc, data, clock, MARKET);
        runs.emptyMarket();
        // the market's own pay model: $5.00 a run and $4.50 a door; the rest are the platform's defaults
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center,
                                                    radius_km, languages, sort, courier_pay)
                        values ('mkt-test-payville', 'market', 'prov-nt', 'NT', 'Payville',
                                '{"en":"Payville","fr":"Payville"}', 'off', 'SRID=4326;POINT(-114.3700 62.4500)', 20,
                                '{en,fr}', 99,
                                '{"perRunCents": 500, "perDropoffCents": 450}')
                        on conflict (id) do update set courier_pay = excluded.courier_pay""").update();
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Pay Bakery", "master");
        butcher = shopFixtures.shop(MARKET, "Pay Butcher", "trusted");
        baker = data.user("Bea Baker");
        butcherOwner = data.user("Bo Butcher");
        data.member(bakery, baker, MerchantRole.OWNER);
        data.member(butcher, butcherOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Brioche", 800, 20);
        steak = shopFixtures.listing(butcher, BUTCHER, "Flank", 1600, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    @Test
    void aFinishedRunIsPaidUnderTheMarketsModel_withItsTips_andPaidOutWeekly() throws Exception {
        var a = flow.place(
                MARKET,
                "pooled",
                data.user("Amara Osei"),
                "T2T 0B8",
                "\"tip\":{\"kind\":\"amount\",\"value\":300}",
                bread,
                steak);
        var b = flow.place(MARKET, "pooled", data.user("Ben Okafor"), "T2A 1A1", bread);
        runs.awaitDelivery(a.orderId());
        runs.awaitDelivery(b.orderId());
        runs.pastCutoff(a.orderId());
        var courier = runs.courierOnShift("Pat Courier");
        var me = TestJwt.courier(courier);
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        var runId = runs.state("select run_id from fulfilment.deliveries where order_id = ?", a.orderId());
        runs.pack(bakery, baker, a.orderId());
        runs.pack(butcher, butcherOwner, a.orderId());
        runs.pack(bakery, baker, b.orderId());
        runs.pickUpAll(courier);
        var run = runs.run(courier);
        runs.dropOff(courier, CourierRuns.stop(run, "dropoff", a.orderId()), a.orderId());
        runs.dropOff(courier, CourierRuns.stop(run, "dropoff", b.orderId()), b.orderId());

        // two shops, two doors: $5.00 + 2 × $1.00 + 2 × $4.50 = $16.00, with the model it was paid under
        var earning = jdbc.sql("""
                        select pickups, dropoffs, run_cents, stops_cents, total_cents, model::text
                          from fulfilment.courier_earnings where run_id = ?""")
                .params(runId)
                .query((rs, _) -> new Object[] {
                    rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6)
                })
                .single();
        assertThat(earning[0]).isEqualTo(2);
        assertThat(earning[1]).isEqualTo(2);
        assertThat(earning[2]).isEqualTo(500L);
        assertThat(earning[3]).isEqualTo(1100L);
        assertThat(earning[4]).isEqualTo(1600L);
        assertThat((String) earning[5]).contains("\"perDropoffCents\": 450").contains("\"perPickupCents\": 100");
        // the ledger: Northline's cost, owed to the courier — once
        assertThat(jdbc.sql("""
                        select coalesce(sum(credit_cents), 0) from payments.ledger_entries
                         where ref_type = 'courier_run' and ref_id = ? and account = ?""")
                        .params(runId, "courier:" + courier)
                        .query(Long.class)
                        .single())
                .isEqualTo(1600);
        // the tip is allocated at the drop-off (with the delivery fee's capture)
        until(() -> runs.count("""
                        select count(*) from payments.courier_tips where order_id = ? and state = 'allocated'""", a.orderId()) == 1);

        var view = body(mvc.perform(get("/api/v1/courier/earnings").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.payCents").value(1600))
                .andExpect(jsonPath("$.today.tipsCents").value(300))
                .andExpect(jsonPath("$.today.runs").value(1))
                .andExpect(jsonPath("$.week.payCents").value(1600))
                .andExpect(jsonPath("$.runs[0].runId").value(runId))
                .andExpect(jsonPath("$.runs[0].tipsCents").value(300))
                .andExpect(jsonPath("$.runs[0].dropoffs").value(2))
                .andExpect(jsonPath("$.balanceCents").value(1900))
                .andExpect(jsonPath("$.payoutAccount").doesNotExist()));
        // days and weeks are in the market's zone (its province's, from the region model)
        assertThat(JsonPath.<String>read(view, "$.zone")).startsWith("America/");
        // only couriers, only with a key-bound token
        mvc.perform(get("/api/v1/courier/earnings").with(TestJwt.courier(data.user("Not A Courier"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_courier"));
        mvc.perform(get("/api/v1/courier/earnings").with(TestJwt.courierBearer(courier)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courier/earnings").with(TestJwt.customer(courier)))
                .andExpect(status().isForbidden());

        // payout onboarding (the fake counts asking for the link as done); the return URL can't be sent elsewhere
        var link = body(mvc.perform(json(
                                post("/api/v1/courier/payout-account/onboarding"),
                                "{\"returnUrl\":\"https://evil.example/x\"}")
                        .with(me))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(link, "$.url"))
                .contains("/courier/payouts")
                .doesNotContain("evil");
        mvc.perform(get("/api/v1/courier/earnings").with(me))
                .andExpect(jsonPath("$.payoutAccount.payoutsEnabled").value(true))
                .andExpect(jsonPath("$.payoutAccount.bankLast4").value("6789"));

        // the weekly run: a transfer to the courier's account and a payout of everything owed
        assertThat(jobs.payCouriers()).isPositive();
        var payout = jdbc.sql("select id, amount_cents, state from payments.courier_payouts where user_id = ?")
                .params(courier)
                .query((rs, _) -> new Object[] {rs.getString(1), rs.getLong(2), rs.getString(3)})
                .single();
        assertThat(payout[1]).isEqualTo(1900L);
        assertThat(payout[2]).isEqualTo("in_transit");
        mvc.perform(get("/api/v1/courier/earnings").with(me))
                .andExpect(jsonPath("$.balanceCents").value(0))
                .andExpect(jsonPath("$.payouts[0].amountCents").value(1900));
        // nothing owed: the next run pays nothing more
        jobs.payCouriers();
        assertThat(runs.count("select count(*) from payments.courier_payouts where user_id = ?", courier))
                .isEqualTo(1);
        // the fake's payout arrives the next business morning
        clock.advance(Duration.ofDays(4));
        jobs.settleCourierPayouts();
        assertThat(runs.state("select state from payments.courier_payouts where user_id = ?", courier))
                .isEqualTo("paid");
    }
}
