package ca.northline.orders;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Mobile gaps part 2 on a goods order (market "Tipville"): a business's own promo code takes its share off its lines
 * only, the tax is on what's left, and the courier's tip rides on the delivery fee's PaymentIntent untaxed. The
 * drop-off captures the fee and the tip, the delivering courier is owed the tip ({@code courier:<id>}); a tip after the
 * delivery is charged and owed to the same courier; finance refunds a tip only for a defined reason. Every posting
 * balances.
 */
@Import(MovableClock.Config.class)
class ShopPromotionsAndTipsApiTest extends IntegrationTest {

    static final String MARKET = "Tipville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    ShopOrderFlow flow;
    String bakery;
    String owner;
    Listing bread;
    Listing steak;
    String staff;

    @BeforeEach
    void market() {
        clock.reset();
        jdbc.sql("update fulfilment.couriers set active = false where market = ?")
                .params(MARKET)
                .update();
        jdbc.sql("update fulfilment.deliveries set state = 'cancelled' where market = ? and state = 'waiting'")
                .params(MARKET)
                .update();
        jdbc.sql("update fulfilment.runs set state = 'done' where market = ? and state <> 'done'")
                .params(MARKET)
                .update();
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Glenmore Bakery", "master");
        var butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher", "trusted");
        owner = data.user("Bea Baker");
        data.member(bakery, owner, MerchantRole.OWNER);
        var butcherOwner = data.user("Bo Butcher");
        data.member(butcher, butcherOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 2000, 20);
        steak = shopFixtures.listing(butcher, BUTCHER, "Ribeye", 3000, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
        staff = data.user("Dee Dispatcher");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** The bakery's own code: 50 % off its lines. */
    String bakeryCode() {
        var code = "BAKE" + Ids.next().substring(20);
        jdbc.sql("""
                        insert into promotions.codes (id, code, kind, percent, starts_at, ends_at, per_customer_limit,
                               funded_by, merchant_id, applies_to, created_by, created_at, updated_at)
                        values (?, ?, 'percent', 50, now() - interval '1 day', now() + interval '7 days', 1,
                                'merchant', ?, '{goods}', 'test', now(), now())""").params(Ids.next(), code, bakery).update();
        return code;
    }

    Map<String, long[]> postings(String refType, List<String> refIds) {
        return jdbc
                .sql("""
                        select account, sum(debit_cents) as d, sum(credit_cents) as c from payments.ledger_entries
                         where ref_type = :t and ref_id = any(:ids) group by account""")
                .param("t", refType)
                .param("ids", refIds.toArray(String[]::new))
                .query((rs, _) -> Map.entry(rs.getString("account"), new long[] {rs.getLong("d"), rs.getLong("c")}))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static void balanced(Map<String, long[]> p) {
        assertThat(p.values().stream().mapToLong(v -> v[0]).sum())
                .isEqualTo(p.values().stream().mapToLong(v -> v[1]).sum());
    }

    /** Places the order with {@code extra}, plans it on a courier's run and has it picked up. {order, customer, courier} */
    List<String> onItsWay(String extra) throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", extra, bread, steak);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select count(*) from fulfilment.deliveries where order_id = ?")
                                .params(order.orderId())
                                .query(Long.class)
                                .single()
                        == 1);
        var orderBy = jdbc.sql("select order_by from fulfilment.deliveries where order_id = ?")
                .params(order.orderId())
                .query((rs, _) -> rs.getTimestamp(1).toInstant())
                .single();
        clock.advance(Duration.between(clock.instant(), orderBy).plusMinutes(1));
        var courier = data.user("Kai Courier");
        var created = body(mvc.perform(json(
                        post("/api/v1/console/fulfilment/couriers"),
                        "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"bike\"}".formatted(courier, MARKET))
                .with(TestJwt.staff(staff, StaffRole.DISPATCH))));
        var now = clock.instant();
        var shift = body(mvc.perform(json(
                        post("/api/v1/console/fulfilment/couriers/{id}/shifts", JsonPath.<String>read(created, "$.id")),
                        "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                .formatted(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(4))))
                .with(TestJwt.staff(staff, StaffRole.DISPATCH))));
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(TestJwt.courier(courier)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{\"market\":\"%s\"}".formatted(MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(jsonPath("$.assigned").value(1));
        for (var shop : List.of(bakery)) {
            mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", shop, order.orderId())
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isOk());
        }
        var butcherOwner =
                jdbc.sql("""
                        select user_id from merchants.merchant_members
                         where merchant_id = ? and role = 'owner'""").params(steak.merchantId()).query(String.class).single();
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", steak.merchantId(), order.orderId())
                        .with(TestJwt.member(butcherOwner)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10))
                .until(() ->
                        jdbc.sql("""
                                        select count(*) from fulfilment.delivery_pickups
                                         where order_id = ? and packed_at is not null""").params(order.orderId()).query(Long.class).single() == 2);
        var run = body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(courier))));
        for (var pickup : (JSONArray) JsonPath.read(run, "$.stops[?(@.kind == 'pickup')].id")) {
            mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", pickup.toString()), "{\"scanOk\":true}")
                            .with(TestJwt.courier(courier)))
                    .andExpect(status().isOk());
        }
        return List.of(order.orderId(), order.customerId(), courier);
    }

    void dropOff(String orderId, String courier) throws Exception {
        var pin = jdbc.sql("select pin from fulfilment.deliveries where order_id = ?")
                .params(orderId)
                .query(String.class)
                .single();
        var drop = ((JSONArray) JsonPath.read(
                        body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(courier)))),
                        "$.stops[?(@.kind == 'dropoff')].id"))
                .getFirst()
                .toString();
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/dropoff", drop),
                                "{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(pin))
                        .with(TestJwt.courier(courier)))
                .andExpect(status().isOk());
    }

    @Test
    void aShopsOwnCodeTakesOffItsLinesOnly_taxedAfter_andTheCheckoutTipGoesToTheCourierWhoDelivers() throws Exception {
        var code = bakeryCode();
        var it = onItsWay("\"promoCode\":\"%s\",\"tip\":{\"kind\":\"amount\",\"value\":300}".formatted(code));
        var orderId = it.get(0);
        var courier = it.get(2);

        // the bakery's $20 bread is $10 after its code; the butcher's $30 steak is untouched; GST on what's left
        var lines = jdbc
                .sql("""
                        select merchant_id, amount_cents, tax_cents, discount_cents, discount_funded_by
                          from payments.escrows e where ref_type = 'order_line'
                           and ref_id in (select id from orders.order_lines where order_id = ?)""")
                .params(orderId)
                .query((rs, _) ->
                        Map.entry(rs.getString(1), List.of(rs.getLong(2), rs.getLong(3), rs.getLong(4), (Object)
                                String.valueOf(rs.getString(5)))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(lines.get(bakery)).containsExactly(1000L, 50L, 1000L, "merchant");
        assertThat(lines.get(steak.merchantId())).containsExactly(3000L, 150L, 0L, "null");
        var order = jdbc.sql("select discount_cents, tip_cents, promo_code from orders.orders where id = ?")
                .params(orderId)
                .query()
                .singleRow();
        assertThat(order)
                .containsEntry("discount_cents", 1000L)
                .containsEntry("tip_cents", 300L)
                .containsEntry("promo_code", code);
        assertThat(jdbc.sql("select state from payments.courier_tips where order_id = ? and source = 'checkout'")
                        .params(orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("pending");

        // the customer's order total: $10 + $30 − 0 + fee + GST + $3 tip
        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(it.get(1))))
                .andExpect(status().isOk());

        dropOff(orderId, courier);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql(
                                "select state from payments.courier_tips where order_id = ? and source = 'checkout'")
                        .params(orderId)
                        .query(String.class)
                        .single()
                        .equals("allocated"));
        var fee = postings("order_delivery", List.of(orderId));
        balanced(fee);
        assertThat(fee.get("courier_tips")[1]).isEqualTo(300); // the tip, untaxed, never revenue
        var tipId = jdbc.sql("select id from payments.courier_tips where order_id = ? and source = 'checkout'")
                .params(orderId)
                .query(String.class)
                .single();
        var moved = postings("courier_tip", List.of(tipId));
        balanced(moved);
        assertThat(moved.get("courier_tips")[0]).isEqualTo(300);
        assertThat(moved.get("courier:" + courier)[1]).isEqualTo(300);

        // a tip after the delivery: its own payment, owed to the same courier
        var me = TestJwt.customer(it.get(1));
        mvc.perform(get("/api/v1/me/orders/{id}/tips", orderId).with(me))
                .andExpect(jsonPath("$.canTip").value(true))
                .andExpect(jsonPath("$.courierFirstName").value("Kai"))
                .andExpect(jsonPath("$.items[0].source").value("checkout"));
        mvc.perform(json(post("/api/v1/me/orders/{id}/tips", orderId), "{\"kind\":\"percent\",\"value\":10}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Idempotency-Key header is required."));
        mvc.perform(json(post("/api/v1/me/orders/{id}/tips", orderId), "{\"kind\":\"amount\",\"value\":20000}")
                        .with(me)
                        .header("Idempotency-Key", Ids.next())
                        .header("Accept-Language", "fr-CA"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Choisissez un pourboire de 0 $ à 100 $, ou jusqu’à 30 %."));
        var started = body(mvc.perform(
                        json(post("/api/v1/me/orders/{id}/tips", orderId), "{\"kind\":\"percent\",\"value\":10}")
                                .with(me)
                                .header("Idempotency-Key", Ids.next()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tip.amountCents").value(500)) // 10 % of the $50 of goods (before the code)
                .andExpect(jsonPath("$.tip.state").value("pending")));
        String after = JsonPath.read(started, "$.tip.id");
        mvc.perform(post("/api/v1/me/orders/{id}/tips/{tip}/confirm", orderId, after)
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("allocated"))
                .andExpect(jsonPath("$.courierUserId").value(courier));
        var owed = postings("courier_tip", List.of(after));
        balanced(owed);
        assertThat(owed.get("stripe_balance")[0]).isEqualTo(500);
        assertThat(owed.get("courier:" + courier)[1]).isEqualTo(500);
        // once
        mvc.perform(json(post("/api/v1/me/orders/{id}/tips", orderId), "{\"kind\":\"amount\",\"value\":200}")
                        .with(me)
                        .header("Idempotency-Key", Ids.next()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_tipped"));
        // someone else's order
        mvc.perform(get("/api/v1/me/orders/{id}/tips", orderId).with(TestJwt.customer(data.user("Nosy"))))
                .andExpect(status().isNotFound());

        // refunds of a tip: finance only, with a defined reason
        var fin = data.user("Fin Ance");
        mvc.perform(json(post("/api/v1/console/tips/{id}/refund", after), "{\"reason\":\"amount_error\"}")
                        .with(TestJwt.staff(fin, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/console/tips/{id}/refund", after), "{\"reason\":\"changed_my_mind\"}")
                        .with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose why the tip is refunded."));
        mvc.perform(json(post("/api/v1/console/tips/{id}/refund", after), "{\"reason\":\"amount_error\"}")
                        .with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("refunded"));
        mvc.perform(json(post("/api/v1/console/tips/{id}/refund", after), "{\"reason\":\"amount_error\"}")
                        .with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(status().isConflict());
        var refunded = postings("courier_tip", List.of(after));
        balanced(refunded);
        assertThat(refunded.get("courier:" + courier)[0]).isEqualTo(500);
        assertThat(refunded.get("stripe_balance")[1]).isEqualTo(500);
        mvc.perform(get("/api/v1/console/orders/{id}/tips", orderId).with(TestJwt.staff(fin, StaffRole.FINANCE)))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void theQuoteShowsTheCodeTheTipAndThePoints_andAnAbandonedCheckoutGivesTheCodeBack() throws Exception {
        var code = bakeryCode();
        var customer = data.user("Quinn Quote");
        jdbc.sql(
                        "insert into trust.points_ledger (id, user_id, delta, ref_type, ref_id) values (?, ?, 2000, 'earn', ?)")
                .params(Ids.next(), customer, Ids.next())
                .update();
        var auth = TestJwt.customerWithMfa(customer);
        for (var l : List.of(bread, steak)) {
            mvc.perform(json(post("/api/v1/cart/items"), "{\"offerId\":\"%s\",\"qty\":1}".formatted(l.offerId()))
                            .with(auth))
                    .andExpect(status().isCreated());
        }
        var setup = body(
                mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(auth)));
        String window = JsonPath.read(setup, "$.options[0].windowId");
        var form = """
                {"kind":"pooled","windowId":"%s","address":{"street":"1 Sample St","city":"%s","province":"AB",
                 "postal":"T2T 0B8"},"substitution":"similar","promoCode":"%s","usePoints":true,
                 "tip":{"kind":"percent","value":10}}""".formatted(window, MARKET, code);
        mvc.perform(json(post("/api/v1/me/checkout/quote"), form).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalCents").value(5000))
                .andExpect(jsonPath("$.discountCents").value(1000))
                .andExpect(jsonPath("$.promoCode").value(code))
                .andExpect(jsonPath("$.tipCents").value(500))
                .andExpect(jsonPath("$.pointsAvailable").value(2000))
                .andExpect(jsonPath("$.pointsCents").value(2000)) // $20 ≤ half of the $40 left
                .andExpect(jsonPath("$.taxCents").isNumber());

        // starting holds the code; a second start (the first abandoned) still has it to use
        mvc.perform(json(post("/api/v1/me/checkouts"), form).with(auth).header("Idempotency-Key", Ids.next()))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/me/checkouts"), form).with(auth).header("Idempotency-Key", Ids.next()))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("""
                        select count(*) from promotions.redemptions r join promotions.codes c on c.id = r.code_id
                         where c.code = ? and r.state = 'reserved'""").params(code).query(Long.class).single()).isEqualTo(1);
        // the points aren't spent until the order is placed
        assertThat(jdbc.sql("select sum(delta) from trust.points_ledger where user_id = ?")
                        .params(customer)
                        .query(Long.class)
                        .single())
                .isEqualTo(2000);
        // an unknown code is refused on the quote, in French too
        mvc.perform(json(post("/api/v1/me/checkout/quote"), form.replace(code, "NOPE-NOPE"))
                        .with(auth)
                        .header("Accept-Language", "fr-CA"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Ce code n’est pas valide."));
    }
}
