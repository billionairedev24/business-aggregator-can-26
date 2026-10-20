package ca.northline.orders;

import static ca.northline.support.CourierRuns.body;
import static ca.northline.support.CourierRuns.json;
import static ca.northline.support.CourierRuns.until;
import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.api.DeliveryFailed;
import ca.northline.orders.api.OrderDeliveryFailed;
import ca.northline.orders.application.AfterSaleUseCases.FailedDeliveries;
import ca.northline.payments.api.OrderRefunds;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.CourierRuns;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * S-151 failed deliveries in market "Missville": the courier can't hand the order over (a photo at the door first), the
 * goods go back to the shop, the customer chooses re-delivery on the next run or a refund — or is refunded when the
 * window passes; the console lists them.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class FailedDeliveryApiTest extends IntegrationTest {

    static final String MARKET = "Missville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    ApplicationEvents events;

    @Autowired
    FailedDeliveries failures;

    @Autowired
    OrderRefunds refunds;

    ShopOrderFlow flow;
    CourierRuns runs;
    String bakery;
    String owner;
    Listing bread;

    @BeforeEach
    void market() {
        clock.reset();
        runs = new CourierRuns(mvc, jdbc, data, clock, MARKET);
        runs.emptyMarket();
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Miss Bakery", "master");
        owner = data.user("Bea Baker");
        data.member(bakery, owner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Seed loaf", 750, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    /** An order on its way: planned, assigned, packed and picked up. Returns {order, courier}. */
    private String[] onItsWay(String customer) throws Exception {
        var order = flow.place(MARKET, "pooled", data.user(customer), "T2T 0B8", bread);
        runs.awaitDelivery(order.orderId());
        runs.pastCutoff(order.orderId());
        var courier = runs.courierOnShift(customer + " Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        runs.pack(bakery, owner, order.orderId());
        runs.pickUpAll(courier);
        runs.awaitOrder(order.orderId(), "picked_up");
        return new String[] {order.orderId(), courier, order.customerId()};
    }

    private String failAtTheDoor(String orderId, String courier) throws Exception {
        var drop = CourierRuns.stop(runs.run(courier), "dropoff", orderId);
        runs.photo(courier, drop).andExpect(status().isOk());
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/fail", drop),
                                "{\"reason\":\"absent\",\"note\":\"Left a card\"}")
                        .with(TestJwt.courier(courier)))
                .andExpect(status().isOk());
        runs.awaitOrder(orderId, "delivery_failed");
        return drop;
    }

    @Test
    void theCourierCantHandItOver_theCustomerChoosesRedeliveryOnTheNextRun() throws Exception {
        var o = onItsWay("Amara Osei");
        var orderId = o[0];
        var courier = o[1];
        var me = TestJwt.courier(courier);
        var drop = CourierRuns.stop(runs.run(courier), "dropoff", orderId);

        // the rules: a reason, a note for "other", a photo first
        mvc.perform(json(post("/api/v1/courier/stops/{id}/fail", drop), "{\"reason\":\"gone\"}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose why the order couldn't be handed over."));
        mvc.perform(json(post("/api/v1/courier/stops/{id}/fail", drop), "{\"reason\":\"other\"}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add a note saying what happened."));
        mvc.perform(json(post("/api/v1/courier/stops/{id}/fail", drop), "{\"reason\":\"absent\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("proof_missing"))
                .andExpect(jsonPath("$.detail").value("Take a photo at the door first."));
        var other = runs.courierOnShift("Other Courier");
        mvc.perform(json(post("/api/v1/courier/stops/{id}/fail", drop), "{\"reason\":\"absent\"}")
                        .with(TestJwt.courier(other)))
                .andExpect(status().isNotFound());
        jdbc.sql("update fulfilment.couriers set active = false where user_id = ?")
                .params(other)
                .update();

        runs.photo(courier, drop).andExpect(status().isOk());
        var after = body(mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/fail", drop),
                                "{\"reason\":\"absent\",\"note\":\"Nobody answered, left a card\"}")
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[?(@.id == '%s')].proofKind".formatted(drop))
                        .value("failed"))
                .andExpect(jsonPath("$.stops[?(@.id == '%s')].failReason".formatted(drop))
                        .value("absent")));
        var back = CourierRuns.stop(after, "return", orderId);
        assertThat(events.stream(DeliveryFailed.class)
                        .filter(e -> e.aggregateId().equals(orderId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.reason()).isEqualTo("absent");
                    assertThat(e.attempt()).isEqualTo(1);
                });
        runs.awaitOrder(orderId, "delivery_failed");
        until(() -> events.stream(OrderDeliveryFailed.class)
                .anyMatch(e -> e.aggregateId().equals(orderId)));
        var customer = TestJwt.customer(o[2]);
        var view = body(mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("delivery_failed"))
                .andExpect(jsonPath("$.deliveryFailure.reason").value("absent"))
                .andExpect(jsonPath("$.deliveryFailure.choice").doesNotExist())
                .andExpect(jsonPath("$.steps[3].state").value("current")));
        assertThat(Duration.between(clock.instant(), Instant.parse(JsonPath.read(view, "$.deliveryFailure.decideBy"))))
                .isBetween(Duration.ofHours(47), Duration.ofHours(48));
        // the door photo is shown to the customer
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", orderId).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());
        // the console lists it
        mvc.perform(get("/api/v1/console/orders")
                        .param("view", "failed")
                        .with(TestJwt.staff(runs.staff(), StaffRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status".formatted(orderId))
                        .value("delivery_failed"));

        // the courier brings it back; the run is done and paid (a pickup, a failed door, a return)
        mvc.perform(post("/api/v1/courier/stops/{id}/returned", back).with(me)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select failed from fulfilment.courier_earnings where courier_user_id = ?")
                        .params(courier)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        // the choice: re-delivery on the next run; once
        mvc.perform(json(post("/api/v1/me/orders/{id}/delivery-failure", orderId), "{\"choice\":\"later\"}")
                        .with(customer))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose re-delivery or a refund."));
        mvc.perform(json(post("/api/v1/me/orders/{id}/delivery-failure", orderId), "{\"choice\":\"redeliver\"}")
                        .with(TestJwt.customer(data.user("Someone Else"))))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/me/orders/{id}/delivery-failure", orderId), "{\"choice\":\"redeliver\"}")
                        .with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.deliveryFailure.choice").value("redeliver"));
        mvc.perform(json(post("/api/v1/me/orders/{id}/delivery-failure", orderId), "{\"choice\":\"refund\"}")
                        .with(customer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_failed"));
        assertThat(runs.state("select state from fulfilment.deliveries where order_id = ?", orderId))
                .isEqualTo("waiting");
        assertThat(runs.count("select attempts from fulfilment.deliveries where order_id = ?", orderId))
                .isEqualTo(2);

        // the next run picks it up again from the shop (still packed) and delivers it
        runs.pastCutoff(orderId);
        jdbc.sql("update fulfilment.couriers set active = false where user_id = ?")
                .params(courier)
                .update();
        var next = runs.courierOnShift("Next Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        runs.pickUpAll(next);
        var drop2 = CourierRuns.stop(runs.run(next), "dropoff", orderId);
        runs.dropOff(next, drop2, orderId);
        runs.awaitOrder(orderId, "delivered");
    }

    @Test
    void theCustomerChoosesARefund_everythingGoesBack() throws Exception {
        var o = onItsWay("Ben Okafor");
        failAtTheDoor(o[0], o[1]);
        mvc.perform(json(post("/api/v1/me/orders/{id}/delivery-failure", o[0]), "{\"choice\":\"refund\"}")
                        .with(TestJwt.customer(o[2])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("refunded"))
                .andExpect(jsonPath("$.deliveryFailure.choice").value("refund"));
        until(() -> "refunded"
                .equals(runs.state(
                        "select e.state from payments.escrows e join orders.order_lines l on l.id = e.ref_id where l.order_id = ?",
                        o[0])));
        until(() -> "canceled"
                .equals(runs.state(
                        "select state from payments.payment_intents where ref_type = 'order_delivery' and ref_id = ? and replaced_by is null",
                        o[0])));
        assertThat(runs.state(
                        "select reason from payments.refunds r join payments.escrows e on e.id = r.escrow_id "
                                + "join orders.order_lines l on l.id = e.ref_id where l.order_id = ?",
                        o[0]))
                .isEqualTo("delivery_failed");
        until(() -> "cancelled".equals(runs.state("select state from fulfilment.deliveries where order_id = ?", o[0])));
        // the goods are back at the shop: on sale again
        assertThat(runs.count("select stock from catalogue.offers where id = ?", bread.offerId()))
                .isEqualTo(20);
    }

    @Test
    void withoutAChoiceInTime_theOrderIsRefunded() throws Exception {
        var o = onItsWay("Cleo Ng");
        failAtTheDoor(o[0], o[1]);
        clock.advance(Duration.ofHours(47));
        assertThat(failures.refundDue()).isZero();
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        assertThat(failures.refundDue()).isEqualTo(1);
        assertThat(runs.state("select state from orders.orders where id = ?", o[0]))
                .isEqualTo("refunded");
        assertThat(runs.state("select choice from orders.delivery_failures where order_id = ?", o[0]))
                .isEqualTo("auto_refund");
        assertThat(failures.refundDue()).isZero();
        mvc.perform(get("/api/v1/me/orders/{id}", o[0]).with(TestJwt.customer(o[2])))
                .andExpect(jsonPath("$.deliveryFailure.choice").value("auto_refund"));
    }

    @Test
    void aFailedDeliveryFeeIsKeptFromTheDeliveryFeeHold_taxIncluded() throws Exception {
        // the fee is configuration (0 by default); the payments side keeps what it is given, never more than the fee
        var order = flow.place(MARKET, "pooled", data.user("Dee Daly"), "T2T 4E4", bread);
        var fee = jdbc.sql("""
                        select amount_cents from payments.payment_intents
                         where ref_type = 'order_delivery' and ref_id = ? and replaced_by is null""").params(order.orderId()).query(Long.class).single();
        refunds.settleDeliveryFee(order.orderId(), 150, clock.instant());
        assertThat(runs.state(
                        "select state from payments.payment_intents where ref_type = 'order_delivery' and ref_id = ? and replaced_by is null",
                        order.orderId()))
                .isEqualTo("captured");
        var credits = jdbc.sql("""
                        select account, sum(credit_cents) from payments.ledger_entries
                         where ref_type = 'order_delivery' and ref_id = ? and credit_cents > 0 group by account""")
                .params(order.orderId())
                .query((rs, _) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list();
        assertThat(credits.stream().mapToLong(Map.Entry::getValue).sum()).isEqualTo(150);
        assertThat(credits).extracting(Map.Entry::getKey).contains("revenue", "tax_payable");
        assertThat(fee).isGreaterThan(150);
        // a second call changes nothing
        refunds.settleDeliveryFee(order.orderId(), 150, clock.instant());
        assertThat(runs.count(
                        "select count(*) from payments.ledger_entries where ref_type = 'order_delivery' and ref_id = ?",
                        order.orderId()))
                .isEqualTo(credits.size() + 1L);
    }
}
