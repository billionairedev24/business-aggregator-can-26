package ca.northline.orders;

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

import ca.northline.fulfilment.api.RunChanged;
import ca.northline.orders.api.OrderCancelled;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.CourierRuns;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-151 cancellations in market "Cancelville", orders placed through the real checkout (fake Stripe): the customer
 * before any shop packs, a shop for its own lines (the rest goes on), Northline staff until delivery — each one's
 * money (card holds voided, the delivery fee's released), stock, delivery stops and run, event, audit and rules.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class OrderCancellationApiTest extends IntegrationTest {

    static final String MARKET = "Cancelville";

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
    String bakeryOwner;
    String butcherOwner;
    Listing bread;
    Listing steak;

    @BeforeEach
    void market() {
        clock.reset();
        runs = new CourierRuns(mvc, jdbc, data, clock, MARKET);
        runs.emptyMarket();
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Cancel Bakery", "master");
        butcher = shopFixtures.shop(MARKET, "Cancel Butcher", "trusted");
        bakeryOwner = data.user("Bea Baker");
        butcherOwner = data.user("Bo Butcher");
        data.member(bakery, bakeryOwner, MerchantRole.OWNER);
        data.member(butcher, butcherOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Rye loaf", 750, 20);
        steak = shopFixtures.listing(butcher, BUTCHER, "Striploin", 1850, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    private long stock(Listing l) {
        return jdbc.sql("select stock from catalogue.offers where id = ?")
                .params(l.offerId())
                .query(Long.class)
                .single();
    }

    private String lineIntent(String lineId) {
        return jdbc.sql("""
                        select state from payments.payment_intents
                         where ref_type = 'order_line' and ref_id = ? and replaced_by is null""").params(lineId).query(String.class).single();
    }

    private String feeIntent(String orderId) {
        return jdbc.sql("""
                        select state from payments.payment_intents
                         where ref_type = 'order_delivery' and ref_id = ? and replaced_by is null""").params(orderId).query(String.class).single();
    }

    private String escrow(String lineId) {
        return jdbc.sql("select state from payments.escrows where ref_type = 'order_line' and ref_id = ?")
                .params(lineId)
                .query(String.class)
                .single();
    }

    @Test
    void theCustomerCancelsBeforeAnyShopPacked_holdsVoidedStockBackRunCancelled() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread, steak);
        var me = TestJwt.customer(order.customerId());
        runs.awaitDelivery(order.orderId());
        assertThat(stock(bread)).isEqualTo(19);
        runs.pastCutoff(order.orderId());
        var courier = runs.courierOnShift("Kai Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        var runId = runs.state("select run_id from fulfilment.deliveries where order_id = ?", order.orderId());

        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(me))
                .andExpect(jsonPath("$.canCancel").value(true));
        // validation, someone else's order, a guest
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"because\"}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a reason."));
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}")
                        .with(TestJwt.customer(data.user("Someone Else"))))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}")
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"))
                .andExpect(jsonPath("$.canCancel").value(false))
                .andExpect(jsonPath("$.cancellations[0].by").value("customer"))
                .andExpect(jsonPath("$.cancellations[0].whole").value(true));
        assertThat(events.stream(OrderCancelled.class)
                        .filter(e -> e.aggregateId().equals(order.orderId())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.whole()).isTrue();
                    assertThat(e.by()).isEqualTo("customer");
                    assertThat(e.lineIds()).containsExactlyInAnyOrderElementsOf(order.lineIds());
                    assertThat(e.merchantIds()).containsExactlyInAnyOrder(bakery, butcher);
                });
        // stock is back at once; the money and the run follow the event
        assertThat(stock(bread)).isEqualTo(20);
        assertThat(stock(steak)).isEqualTo(20);
        for (var line : order.lineIds()) {
            until(() -> "refunded".equals(escrow(line)));
            assertThat(lineIntent(line)).isEqualTo("canceled");
        }
        until(() -> "canceled".equals(feeIntent(order.orderId())));
        // a void posts nothing: no refund moved card money
        assertThat(jdbc.sql("""
                        select count(*) from payments.refunds r
                         where r.escrow_id in (select id from payments.escrows where ref_id in (:ids))
                           and r.reason = 'order_cancelled' and r.state = 'paid' and r.stripe_refund is null""").param("ids", order.lineIds()).query(Long.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                        select count(*) from payments.ledger_entries where ref_type = 'refund' and ref_id in
                          (select r.id from payments.refunds r join payments.escrows e on e.id = r.escrow_id
                            where e.ref_id in (:ids))""").param("ids", order.lineIds()).query(Long.class).single())
                .isZero();
        // the run had only this order: cancelled, the courier free and told
        until(() -> "cancelled".equals(runs.state("select state from fulfilment.runs where id = ?", runId)));
        assertThat(runs.state("select state from fulfilment.deliveries where order_id = ?", order.orderId()))
                .isEqualTo("cancelled");
        until(() -> events.stream(RunChanged.class)
                .anyMatch(e -> e.aggregateId().equals(runId) && e.change().equals("cancelled")));
        mvc.perform(get("/api/v1/courier/me").with(TestJwt.courier(courier)))
                .andExpect(jsonPath("$.status").value("available"));
        mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(courier))).andExpect(status().isNoContent());
        // once is enough
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_closed"));
    }

    @Test
    void theCustomerCanNoLongerCancelOnceAShopPacked() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Ben Okafor"), "T2A 1A1", bread, steak);
        runs.awaitDelivery(order.orderId());
        runs.pack(bakery, bakeryOwner, order.orderId());
        var me = TestJwt.customer(order.customerId());
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(me))
                .andExpect(jsonPath("$.canCancel").value(false));
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("too_late"))
                .andExpect(jsonPath("$.detail")
                        .value("A shop has started packing this order, so it can't be cancelled now. You can return it"
                                + " after delivery."));
        mvc.perform(json(post("/api/v1/me/orders/{id}/cancel", order.orderId()), "{\"reason\":\"changed_mind\"}")
                        .header("Accept-Language", "fr-CA")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Un commerce a commencé")));
        assertThat(runs.state("select state from orders.orders where id = ?", order.orderId()))
                .isEqualTo("packing");
    }

    @Test
    void aShopCancelsItsOwnLines_theRestOfTheOrderGoesOn() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Cleo Ng"), "T3K 5Z9", bread, steak);
        runs.awaitDelivery(order.orderId());
        until(() -> runs.count("select count(*) from fulfilment.delivery_pickups where order_id = ?", order.orderId())
                == 2);
        runs.pastCutoff(order.orderId());
        runs.courierOnShift("Lou Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        var runId = runs.state("select run_id from fulfilment.deliveries where order_id = ?", order.orderId());
        var steakLine = jdbc.sql("select id from orders.order_lines where order_id = ? and merchant_id = ?")
                .params(order.orderId(), butcher)
                .query(String.class)
                .single();
        var breadLine = order.lineIds().stream()
                .filter(l -> !l.equals(steakLine))
                .findFirst()
                .orElseThrow();
        var cancel = "/api/v1/merchants/{m}/orders/{o}/cancel";

        // rules of the endpoint: membership, second factor, the shop's own order, the note for "other"
        mvc.perform(json(post(cancel, butcher, order.orderId()), "{\"reason\":\"out_of_stock\"}")
                        .with(TestJwt.member(data.user("Not A Member"))))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(cancel, butcher, order.orderId()), "{\"reason\":\"out_of_stock\"}")
                        .with(TestJwt.memberWithoutMfa(butcherOwner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        var stranger = shopFixtures.shop(MARKET, "Other Shop", "trusted");
        var strangerOwner = data.user("Ola Other");
        data.member(stranger, strangerOwner, MerchantRole.OWNER);
        mvc.perform(json(post(cancel, stranger, order.orderId()), "{\"reason\":\"out_of_stock\"}")
                        .with(TestJwt.member(strangerOwner)))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(cancel, butcher, order.orderId()), "{\"reason\":\"other\"}")
                        .with(TestJwt.member(butcherOwner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add a note for the reason “other”."));

        mvc.perform(json(
                                post(cancel, butcher, order.orderId()),
                                "{\"reason\":\"out_of_stock\",\"note\":\"Freezer failed\",\"restock\":false}")
                        .with(TestJwt.member(butcherOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(events.stream(OrderCancelled.class)
                        .filter(e -> e.aggregateId().equals(order.orderId())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.whole()).isFalse();
                    assertThat(e.merchantIds()).containsExactly(butcher);
                    assertThat(e.lineIds()).containsExactly(steakLine);
                    assertThat(e.state()).isEqualTo("placed");
                });
        assertThat(stock(steak)).isEqualTo(19); // the shop chose not to restock
        until(() -> "refunded".equals(escrow(steakLine)));
        assertThat(escrow(breadLine)).isEqualTo("held");
        assertThat(lineIntent(breadLine)).isEqualTo("authorized");
        assertThat(feeIntent(order.orderId())).isEqualTo("authorized");
        // the butcher's pickup left the run, the bakery's is still there; the courier is told
        until(() -> runs.count(
                        "select count(*) from fulfilment.stops where run_id = ? and state <> 'cancelled' and kind = 'pickup'",
                        runId)
                == 1);
        until(() -> events.stream(RunChanged.class)
                .anyMatch(e -> e.aggregateId().equals(runId) && e.change().equals("stops_removed")));
        assertThat(runs.count("select count(*) from fulfilment.delivery_pickups where order_id = ?", order.orderId()))
                .isEqualTo(1);
        // the customer sees which shop cancelled; the order is still on its way
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(TestJwt.customer(order.customerId())))
                .andExpect(jsonPath("$.state").value("placed"))
                .andExpect(jsonPath("$.cancellations[0].merchantName").value("Cancel Butcher"))
                .andExpect(jsonPath("$.shops[?(@.merchantId == '%s')].cancelled".formatted(butcher))
                        .value(true));
        // nothing left for the butcher to cancel
        mvc.perform(json(post(cancel, butcher, order.orderId()), "{\"reason\":\"out_of_stock\"}")
                        .with(TestJwt.member(butcherOwner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nothing_to_cancel"));

        // the last shop cancelling closes the order and releases the delivery fee
        mvc.perform(json(post(cancel, bakery, order.orderId()), "{\"reason\":\"cannot_fulfil\",\"restock\":true}")
                        .with(TestJwt.member(bakeryOwner)))
                .andExpect(status().isOk());
        assertThat(runs.state("select state from orders.orders where id = ?", order.orderId()))
                .isEqualTo("cancelled");
        assertThat(stock(bread)).isEqualTo(20);
        until(() -> "canceled".equals(feeIntent(order.orderId())));
        until(() -> "cancelled".equals(runs.state("select state from fulfilment.runs where id = ?", runId)));
    }

    @Test
    void aShopCanNoLongerCancelWhatTheCourierPickedUp() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Dee Daly"), "T2T 1B1", bread);
        runs.awaitDelivery(order.orderId());
        runs.pastCutoff(order.orderId());
        var courier = runs.courierOnShift("Max Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        runs.pack(bakery, bakeryOwner, order.orderId());
        runs.pickUpAll(courier);
        runs.awaitOrder(order.orderId(), "picked_up");
        mvc.perform(json(
                                post("/api/v1/merchants/{m}/orders/{o}/cancel", bakery, order.orderId()),
                                "{\"reason\":\"out_of_stock\"}")
                        .with(TestJwt.member(bakeryOwner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("too_late"))
                .andExpect(jsonPath("$.detail")
                        .value("The courier has picked this order up, so it can't be cancelled now."));
    }

    @Test
    void staffCancelUntilDelivery_supportOnly_audited_andWhatTheCourierCarriesGoesBack() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Eli Moss"), "T2T 2C2", bread);
        runs.awaitDelivery(order.orderId());
        runs.pastCutoff(order.orderId());
        var courier = runs.courierOnShift("Noa Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        runs.pack(bakery, bakeryOwner, order.orderId());
        runs.pickUpAll(courier);
        runs.awaitOrder(order.orderId(), "picked_up");
        var agent = data.user("Sam Support");
        var url = "/api/v1/console/orders/{id}/cancel";
        var body = "{\"reason\":\"fraud\",\"note\":\"Card reported stolen\",\"restock\":true}";
        mvc.perform(json(post(url, order.orderId()), body).with(TestJwt.staff(agent, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(url, order.orderId()), body).with(TestJwt.staffWithoutMfa(agent, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(url, order.orderId()), "{\"reason\":\"oops\"}")
                        .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a reason."));

        var done =
                body(mvc.perform(json(post(url, order.orderId()), body).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("cancelled"))
                        .andExpect(jsonPath("$.whole").value(true)));
        assertThat(JsonPath.<List<String>>read(done, "$.lineIds")).containsExactlyElementsOf(order.lineIds());
        // audited with codes only — the note stays on the order (the console's drawer)
        assertThat(jdbc.sql("""
                        select after::text from developer.audit_log
                         where action = 'orders.order_cancelled' and target_id = ? and actor_id = ?""")
                        .params(order.orderId(), agent)
                        .query(String.class)
                        .single())
                .contains("fraud")
                .doesNotContain("stolen");
        mvc.perform(get("/api/v1/console/orders/{id}/after-sale", order.orderId())
                        .with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[0].by").value("staff"))
                .andExpect(jsonPath("$.cancellations[0].note").value("Card reported stolen"));
        // the courier had it: the drop-off is gone and a stop back at the shop is added
        until(() -> "returning"
                .equals(runs.state("select state from fulfilment.deliveries where order_id = ?", order.orderId())));
        var run = runs.run(courier);
        assertThat(CourierRuns.stops(run, "dropoff")).isEmpty();
        var back = CourierRuns.stop(run, "return", order.orderId());
        mvc.perform(post("/api/v1/courier/stops/{id}/returned", back).with(TestJwt.courier(courier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"));
        until(() -> "returned"
                .equals(runs.state("select state from fulfilment.deliveries where order_id = ?", order.orderId())));

        // a delivered order can't be cancelled (a return instead)
        var delivered = flow.place(MARKET, "pooled", data.user("Fay Lund"), "T2T 3D3", bread);
        jdbc.sql("update orders.orders set state = 'delivered', delivered_at = now() where id = ?")
                .params(delivered.orderId())
                .update();
        mvc.perform(json(post(url, delivered.orderId()), body).with(TestJwt.staff(agent, StaffRole.SUPPORT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This order was delivered: start a return instead."));
    }
}
