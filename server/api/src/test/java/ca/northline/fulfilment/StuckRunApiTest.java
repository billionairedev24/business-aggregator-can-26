package ca.northline.fulfilment;

import static ca.northline.support.CourierRuns.body;
import static ca.northline.support.CourierRuns.json;
import static ca.northline.support.CourierRuns.until;
import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.api.DeliveryAssigned;
import ca.northline.fulfilment.api.RunChanged;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.CourierRuns;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-151 in market "Stuckville": a started run is stuck, so dispatch moves the orders not picked up yet to another
 * courier — a new part of the same window; what the first courier already carries stays with them.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class StuckRunApiTest extends IntegrationTest {

    static final String MARKET = "Stuckville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    ApplicationEvents events;

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
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Stuck Bakery", "master");
        butcher = shopFixtures.shop(MARKET, "Stuck Butcher", "trusted");
        baker = data.user("Bea Baker");
        butcherOwner = data.user("Bo Butcher");
        data.member(bakery, baker, MerchantRole.OWNER);
        data.member(butcher, butcherOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Focaccia", 700, 20);
        steak = shopFixtures.listing(butcher, BUTCHER, "Brisket", 2200, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    @Test
    void theOrdersNotPickedUpYetMoveToAnotherCourier() throws Exception {
        var a = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread);
        var b = flow.place(MARKET, "pooled", data.user("Ben Okafor"), "T2A 1A1", steak);
        runs.awaitDelivery(a.orderId());
        runs.awaitDelivery(b.orderId());
        runs.pastCutoff(a.orderId());
        var first = runs.courierOnShift("First Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        var runId = runs.state("select run_id from fulfilment.deliveries where order_id = ?", a.orderId());
        var second = runs.courierOnShift("Second Courier");
        var secondId = runs.courierId(second);
        var staff = TestJwt.staff(runs.staff(), StaffRole.DISPATCH);
        var url = "/api/v1/console/fulfilment/runs/{id}/move-remaining";
        var body = "{\"courierId\":\"%s\"}".formatted(secondId);

        // a run that hasn't started is reassigned whole (S-86), not split
        mvc.perform(json(post(url, runId), body).with(staff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("run_not_started"));

        // the first courier picks up A at the bakery; the butcher hasn't packed B
        runs.pack(bakery, baker, a.orderId());
        var run = runs.run(first);
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/pickup", CourierRuns.stop(run, "pickup", a.orderId())),
                                "{\"scanOk\":true}")
                        .with(TestJwt.courier(first)))
                .andExpect(status().isOk());

        // who may, and with whom
        mvc.perform(json(post(url, runId), body).with(TestJwt.staff(runs.staff(), StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(url, runId), "{\"courierId\":\"%s\"}".formatted(runs.courierId(first)))
                        .with(staff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("courier_busy"));
        mvc.perform(json(post(url, runId), "{\"courierId\":\"\"}").with(staff))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a courier."));

        var moved = body(mvc.perform(json(post(url, runId), body).with(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courier.id").value(secondId))
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.stopsTotal").value(2)));
        String newRun = JsonPath.read(moved, "$.id");
        assertThat(runs.state("select run_id from fulfilment.deliveries where order_id = ?", b.orderId()))
                .isEqualTo(newRun);
        assertThat(runs.state("select run_id from fulfilment.deliveries where order_id = ?", a.orderId()))
                .isEqualTo(runId);
        assertThat(events.stream(RunChanged.class))
                .anyMatch(e -> e.aggregateId().equals(runId) && e.change().equals("stops_moved"));
        assertThat(events.stream(DeliveryAssigned.class))
                .anyMatch(e -> e.aggregateId().equals(newRun) && e.orderIds().contains(b.orderId()));
        assertThat(runs.count(
                        "select count(*) from developer.audit_log where action = 'fulfilment.run_split' and target_id = ?",
                        runId))
                .isEqualTo(1);
        // the first courier keeps A only; nothing left to move
        assertThat(CourierRuns.stops(runs.run(first), "pickup")).hasSize(1);
        mvc.perform(json(
                                post(url, runId),
                                "{\"courierId\":\"%s\"}"
                                        .formatted(runs.courierId(runs.courierOnShift("Third Courier"))))
                        .with(staff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_to_move"));

        // both finish
        runs.dropOff(first, CourierRuns.stop(runs.run(first), "dropoff", a.orderId()), a.orderId());
        runs.pack(butcher, butcherOwner, b.orderId());
        runs.pickUpAll(second);
        runs.dropOff(second, CourierRuns.stop(runs.run(second), "dropoff", b.orderId()), b.orderId());
        runs.awaitOrder(a.orderId(), "delivered");
        runs.awaitOrder(b.orderId(), "delivered");
        until(() -> runs.count(
                        "select count(*) from fulfilment.courier_earnings where run_id in (?, '" + newRun + "')", runId)
                == 2);
    }
}
