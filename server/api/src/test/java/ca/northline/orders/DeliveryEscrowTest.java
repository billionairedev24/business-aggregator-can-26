package ca.northline.orders;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.api.DeliveryCompleted;
import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.ShopOrderFlow.Placed;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-78: goods delivery drives escrow. The courier's drop-off ({@code delivery.completed}) starts each line's 7-day
 * window and captures the delivery fee; the customer's confirmation releases at once; a refund case opened in the
 * window pauses the release until it closes. The application clock is moved ({@link MovableClock}); market
 * "Deliveryville".
 */
@Import(MovableClock.Config.class)
class DeliveryEscrowTest extends IntegrationTest {

    static final String MARKET = "Deliveryville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    CustomerCases cases;

    @Autowired
    DisputeDecisions decisions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    ShopOrderFlow flow;
    Listing bread;
    Listing steak;

    @BeforeEach
    void shops() {
        clock.reset();
        shopFixtures.categories();
        var bakery = shopFixtures.shop(MARKET, "Glenmore Bakery", "master");
        var butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher", "trusted");
        bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 750, 10);
        steak = shopFixtures.listing(butcher, BUTCHER, "Ribeye", 1850, 3);
        flow = new ShopOrderFlow(mvc, jdbc, data);
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private record EscrowRow(String state, @Nullable Instant fulfilledAt, @Nullable Instant releaseAt) {}

    private EscrowRow escrow(String lineId) {
        return jdbc.sql("""
                        select state, fulfilled_at, release_at from payments.escrows
                         where ref_type = 'order_line' and ref_id = ?""")
                .params(lineId)
                .query((rs, _) -> new EscrowRow(
                        rs.getString(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .single();
    }

    private String escrowId(String lineId) {
        return jdbc.sql("select id from payments.escrows where ref_type = 'order_line' and ref_id = ?")
                .params(lineId)
                .query(String.class)
                .single();
    }

    private String orderState(String orderId) {
        return jdbc.sql("select state from orders.orders where id = ?")
                .params(orderId)
                .query(String.class)
                .single();
    }

    private String deliveryIntentState(String orderId) {
        return jdbc.sql("""
                        select state from payments.payment_intents
                         where ref_type = 'order_delivery' and ref_id = ? and replaced_by is null""")
                .params(orderId)
                .query(String.class)
                .single();
    }

    private Map<String, Long> deliveryFeeCredits(String orderId) {
        return jdbc.sql("""
                        select account, sum(credit_cents) as credit from payments.ledger_entries
                         where ref_type = 'order_delivery' and ref_id = ? and credit_cents > 0 group by account""")
                .params(orderId)
                .query((rs, _) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void dropOff(String orderId, Instant at) {
        tx.executeWithoutResult(_ -> publisher.publishEvent(
                new DeliveryCompleted(Ids.next(), at, orderId, null, null, null, "photo")));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Placed delivered() throws Exception {
        var order = flow.pooled(MARKET, bread, steak);
        var at = now();
        dropOff(order.orderId(), at);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> order.lineIds().stream().allMatch(l -> escrow(l).fulfilledAt() != null));
        return order;
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theDropOffStartsTheSevenDayWindowCapturesTheFeeAndReleasesWhenItEnds() throws Exception {
        var order = flow.pooled(MARKET, bread, steak);
        assertThat(order.lineIds()).hasSize(2);
        assertThat(deliveryIntentState(order.orderId())).isEqualTo("authorized");
        var at = now();
        dropOff(order.orderId(), at);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> order.lineIds().stream().allMatch(l -> escrow(l).fulfilledAt() != null));
        assertThat(orderState(order.orderId())).isEqualTo("delivered");
        assertThat(jdbc.sql("select delivery_proof from orders.orders where id = ?")
                        .params(order.orderId())
                        .query(String.class)
                        .single())
                .isEqualTo("photo");
        for (var line : order.lineIds()) {
            var e = escrow(line);
            assertThat(e.state()).isEqualTo("held");
            assertThat(e.fulfilledAt()).isEqualTo(at);
            assertThat(Duration.between(at, e.releaseAt())).isEqualTo(Duration.ofDays(7));
        }
        // the delivery fee's hold (S-51) is captured: Northline's revenue and the fee's GST
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveryIntentState(order.orderId())
                .equals("captured"));
        var fee = jdbc.sql("select delivery_fee_cents from orders.orders where id = ?")
                .params(order.orderId())
                .query(Long.class)
                .single();
        var credits = deliveryFeeCredits(order.orderId());
        assertThat(credits).containsEntry("revenue", fee).containsKey("tax_payable");
        assertThat(credits.get("tax_payable")).isPositive();

        // the customer sees when the shops are paid
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(TestJwt.customer(order.customerId())))
                .andExpect(jsonPath("$.state").value("delivered"))
                .andExpect(jsonPath("$.deliveryProof").value("photo"))
                .andExpect(jsonPath("$.canConfirm").value(true))
                .andExpect(jsonPath("$.paysShopsAt").value(at.plus(Duration.ofDays(7)).toString()));

        // a minute before the window ends nothing is released; after it, both lines are
        clock.advance(Duration.ofDays(7).minusMinutes(1));
        jobs.releaseDueEscrows();
        order.lineIds().forEach(l -> assertThat(escrow(l).state()).isEqualTo("held"));
        clock.advance(Duration.ofMinutes(2));
        jobs.releaseDueEscrows();
        order.lineIds().forEach(l -> assertThat(escrow(l).state()).isEqualTo("released"));
    }

    @Test
    void theCustomersConfirmationReleasesAtOnce() throws Exception {
        var order = delivered();
        clock.advance(Duration.ofDays(1));
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId())
                        .with(TestJwt.customer(order.customerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.canConfirm").value(false))
                .andExpect(jsonPath("$.steps[3].state").value("done"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> order.lineIds().stream()
                        .allMatch(l -> escrow(l).state().equals("released")));
        // confirming again changes nothing
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId())
                        .with(TestJwt.customer(order.customerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"));
        // a late or replayed drop-off doesn't move a confirmed order back
        dropOff(order.orderId(), now());
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(orderState(order.orderId())).isEqualTo("confirmed");
    }

    @Test
    void confirmingOnTheWayCountsAsTheDelivery_beforePickupItCant() throws Exception {
        var order = flow.pooled(MARKET, bread);
        var me = TestJwt.customer(order.customerId());
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId()).with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_delivered"))
                .andExpect(jsonPath("$.detail").value("Your order hasn't been delivered yet."));
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId())
                        .with(TestJwt.customer(data.user("Someone Else"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId())).andExpect(status().isUnauthorized());

        jdbc.sql("update orders.orders set state = 'picked_up' where id = ?")
                .params(order.orderId())
                .update();
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", order.orderId()).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> escrow(order.lineIds().getFirst()).state().equals("released"));
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveryIntentState(order.orderId())
                .equals("captured"));

        var cancelled = flow.pooled(MARKET, bread);
        jdbc.sql("update orders.orders set state = 'cancelled' where id = ?")
                .params(cancelled.orderId())
                .update();
        mvc.perform(post("/api/v1/me/orders/{id}/confirm", cancelled.orderId())
                        .with(TestJwt.customer(cancelled.customerId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_closed"));
    }

    @Test
    void aRefundCaseOpenedInTheWindowPausesTheReleaseUntilItCloses() throws Exception {
        var order = delivered();
        var spoiled = order.lineIds().get(0);
        var fine = order.lineIds().get(1);
        clock.advance(Duration.ofDays(2));
        var refundId = cases.requestReview(escrowId(spoiled), order.customerId(), 500, "Damaged");
        assertThat(escrow(spoiled).state()).isEqualTo("disputed");

        clock.advance(Duration.ofDays(6));
        jobs.releaseDueEscrows();
        assertThat(escrow(fine).state()).isEqualTo("released");
        assertThat(escrow(spoiled).state()).isEqualTo("disputed");

        // a Northline agent denies the refund: the hold lifts, and the window is long over
        decisions.decideRefund(refundId, false, data.user("Agent"));
        assertThat(escrow(spoiled).state()).isEqualTo("held");
        jobs.releaseDueEscrows();
        assertThat(escrow(spoiled).state()).isEqualTo("released");
    }

    @Test
    void aRepeatedDropOffKeepsTheFirstTime_andFoodOrdersAreDeliveredToo() throws Exception {
        var order = delivered();
        var first = escrow(order.lineIds().getFirst()).releaseAt();
        clock.advance(Duration.ofHours(3));
        dropOff(order.orderId(), now());
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(escrow(order.lineIds().getFirst()).releaseAt()).isEqualTo(first);

        var food = Ids.next();
        jdbc.sql("insert into orders.orders (id, type, state) values (?, 'food', 'picked_up')")
                .params(food)
                .update();
        dropOff(food, now());
        await().atMost(Duration.ofSeconds(10)).until(() -> orderState(food).equals("delivered"));
        assertThat(List.of(orderState(food))).containsExactly("delivered");
    }
}
