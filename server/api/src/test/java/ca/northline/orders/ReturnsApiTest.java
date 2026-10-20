package ca.northline.orders;

import static ca.northline.support.CourierRuns.body;
import static ca.northline.support.CourierRuns.json;
import static ca.northline.support.CourierRuns.until;
import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.CLOTHING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.orders.api.ReturnUpdated;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.CourierRuns;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.support.ShopOrderFlow.Placed;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-151 returns in market "Returnville": the customer asks within the category's window with a reason and a photo,
 * the shop approves (drop-off, or a pickup on a run) or declines (→ a Northline agent), and the goods back at the shop
 * are refunded at once — the card refunded with its tax, restocked where the shop chose.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class ReturnsApiTest extends IntegrationTest {

    static final String MARKET = "Returnville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    ApplicationEvents events;

    ShopOrderFlow flow;
    CourierRuns runs;
    String shop;
    String owner;
    String bakery;
    String baker;
    Listing shirt;
    Listing bread;
    String courier;

    @BeforeEach
    void market() throws Exception {
        clock.reset();
        runs = new CourierRuns(mvc, jdbc, data, clock, MARKET);
        runs.emptyMarket();
        shopFixtures.categories();
        shop = shopFixtures.shop(MARKET, "Return Outfitters", "master");
        owner = data.user("Olu Owner");
        data.member(shop, owner, MerchantRole.OWNER);
        bakery = shopFixtures.shop(MARKET, "Return Bakery", "trusted");
        baker = data.user("Bea Baker");
        data.member(bakery, baker, MerchantRole.OWNER);
        shirt = shopFixtures.listing(shop, CLOTHING, "Flannel shirt", 4500, 10);
        bread = shopFixtures.listing(bakery, BAKERY, "Bagels", 900, 10);
        flow = new ShopOrderFlow(mvc, jdbc, data);
        courier = null;
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    /** An order of {@code listings} delivered by a courier (its escrow captured: the 7-day window running). */
    private Placed delivered(String customer, Listing... listings) throws Exception {
        var order = flow.place(MARKET, "pooled", data.user(customer), "T2T 0B8", listings);
        runs.awaitDelivery(order.orderId());
        runs.pastCutoff(order.orderId());
        courier = runs.courierOnShift(customer + " Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        for (var l : listings) {
            runs.pack(l.merchantId(), l.merchantId().equals(shop) ? owner : baker, order.orderId());
        }
        runs.pickUp(courier, order.orderId());
        runs.dropOff(courier, CourierRuns.stop(runs.run(courier), "dropoff", order.orderId()), order.orderId());
        runs.awaitOrder(order.orderId(), "delivered");
        until(() -> runs.count(
                        "select count(*) from payments.escrows where ref_type = 'order_line' and fulfilled_at is not null"
                                + " and ref_id in (select id from orders.order_lines where order_id = ?)",
                        order.orderId())
                == listings.length);
        return order;
    }

    private ResultActions ask(Placed order, String reason, byte[] photo, String... lines) throws Exception {
        var request = multipart("/api/v1/me/orders/{id}/returns", order.orderId())
                .param("reason", reason)
                .param("note", "Too small at the shoulders");
        for (var l : lines) {
            request.param("lineIds", l);
        }
        if (photo.length > 0) {
            request.file(new MockMultipartFile("photo", "item.jpg", "image/jpeg", photo));
        }
        return mvc.perform(request.with(TestJwt.customer(order.customerId())));
    }

    private String line(Placed order, String merchantId) {
        return jdbc.sql("select id from orders.order_lines where order_id = ? and merchant_id = ?")
                .params(order.orderId(), merchantId)
                .query(String.class)
                .single();
    }

    private String escrow(String lineId) {
        return runs.state("select state from payments.escrows where ref_type = 'order_line' and ref_id = ?", lineId);
    }

    @Test
    void aReturnDroppedOffAtTheShop_isRefundedWithItsTaxAndRestocked() throws Exception {
        // not before delivery
        var early = flow.place(MARKET, "pooled", data.user("Early Bird"), "T2T 0B8", shirt);
        ask(early, "damaged", CourierRuns.jpeg(), early.lineIds().getFirst())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_delivered"));

        var order = delivered("Amara Osei", shirt);
        var shirtLine = line(order, shop);
        assertThat(runs.count("select stock from catalogue.offers where id = ?", shirt.offerId()))
                .isEqualTo(8);
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(TestJwt.customer(order.customerId())))
                .andExpect(jsonPath("$.returnable[0].lineId").value(shirtLine));

        // the rules: a reason, a photo that is an image, lines of this order
        ask(order, "meh", CourierRuns.jpeg(), shirtLine)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose why you're returning it."));
        ask(order, "damaged", new byte[0], shirtLine)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add a photo of the item."));
        ask(order, "damaged", "not an image".getBytes(StandardCharsets.UTF_8), shirtLine)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Upload a JPG, PNG or WebP image under 5 MB."));
        ask(order, "damaged", CourierRuns.jpeg(), early.lineIds().getFirst())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose what you're returning."));
        mvc.perform(multipart("/api/v1/me/orders/{id}/returns", order.orderId())
                        .file(new MockMultipartFile("photo", "item.jpg", "image/jpeg", CourierRuns.jpeg()))
                        .param("reason", "damaged")
                        .param("lineIds", shirtLine)
                        .with(TestJwt.customer(data.user("Someone Else"))))
                .andExpect(status().isNotFound());

        var asked = body(ask(order, "not_as_described", CourierRuns.jpeg(), shirtLine)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ref").value(org.hamcrest.Matchers.startsWith("RT-")))
                .andExpect(jsonPath("$.state").value("requested"))
                .andExpect(jsonPath("$.amountCents").value(4500))
                .andExpect(jsonPath("$.lines[0].title").value("Flannel shirt"))
                .andExpect(jsonPath("$.canWithdraw").value(true)));
        String returnId = JsonPath.read(asked, "$.id");
        // the money waits: the escrow is on hold, so the 7-day release doesn't pay the shop
        assertThat(escrow(shirtLine)).isEqualTo("disputed");
        ask(order, "damaged", CourierRuns.jpeg(), shirtLine)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_returning"));
        assertThat(events.stream(ReturnUpdated.class)
                        .filter(e -> e.aggregateId().equals(returnId)))
                .singleElement()
                .satisfies(e -> assertThat(e.state()).isEqualTo("requested"));
        // the Studio's badge counts it
        mvc.perform(get("/api/v1/merchants/{m}/nav-badges", shop).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.orders").value(org.hamcrest.Matchers.containsString("1 return")));

        // the shop: its list, the photo; others can't
        var base = "/api/v1/merchants/{m}/returns";
        mvc.perform(get(base, shop).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(returnId))
                .andExpect(jsonPath("$.items[0].note").value("Too small at the shoulders"))
                .andExpect(jsonPath("$.items[0].hasPhoto").value(true));
        mvc.perform(get(base + "/{r}/photo", shop, returnId).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(CourierRuns.jpeg()));
        mvc.perform(get(base, shop).with(TestJwt.member(data.user("Not A Member"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "/{r}", bakery, returnId).with(TestJwt.member(baker)))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(base + "/{r}/approve", shop, returnId), "{\"method\":\"mail\"}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose pickup or drop-off."));
        mvc.perform(json(post(base + "/{r}/approve", shop, returnId), "{\"method\":\"dropoff\"}")
                        .with(TestJwt.memberWithoutMfa(owner)))
                .andExpect(status().isForbidden());

        mvc.perform(json(post(base + "/{r}/approve", shop, returnId), "{\"method\":\"dropoff\",\"restock\":true}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"))
                .andExpect(jsonPath("$.method").value("dropoff"));
        mvc.perform(post(base + "/{r}/received", shop, returnId).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("refunded"))
                .andExpect(jsonPath("$.refundedAt").isNotEmpty());
        // the card refunded with the tax on it, the tax reversed in the ledger; restocked; the order refunded
        assertThat(escrow(shirtLine)).isEqualTo("refunded");
        var refund = jdbc.sql("""
                        select r.id, r.reason, r.state, r.stripe_refund, r.amount_cents from payments.refunds r
                          join payments.escrows e on e.id = r.escrow_id where e.ref_id = ?""")
                .params(shirtLine)
                .query((rs, _) -> new String[] {
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)
                })
                .single();
        assertThat(refund[1]).isEqualTo("returned");
        assertThat(refund[2]).isEqualTo("paid");
        assertThat(refund[3]).startsWith("re_");
        assertThat(refund[4]).isEqualTo("4500");
        assertThat(jdbc.sql("""
                        select coalesce(sum(debit_cents), 0) from payments.ledger_entries
                         where ref_type = 'refund' and ref_id = ? and account = 'tax_payable'""").params(refund[0]).query(Long.class).single()).isPositive();
        assertThat(runs.count("select stock from catalogue.offers where id = ?", shirt.offerId()))
                .isEqualTo(9);
        assertThat(runs.state("select state from orders.orders where id = ?", order.orderId()))
                .isEqualTo("refunded");
        assertThat(events.stream(ReturnUpdated.class)
                        .filter(e -> e.aggregateId().equals(returnId)))
                .extracting(ReturnUpdated::state)
                .containsExactly("requested", "approved", "refunded");
        // a settled return can't change
        mvc.perform(post("/api/v1/me/returns/{r}/withdraw", returnId).with(TestJwt.customer(order.customerId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("return_state"));
    }

    @Test
    void aReturnApprovedForPickup_ridesOnTheNextPooledRun() throws Exception {
        var order = delivered("Ben Okafor", shirt);
        var shirtLine = line(order, shop);
        String returnId = JsonPath.read(
                body(ask(order, "wrong_item", CourierRuns.jpeg(), shirtLine).andExpect(status().isCreated())), "$.id");
        mvc.perform(json(post("/api/v1/merchants/{m}/returns/{r}/approve", shop, returnId), "{\"method\":\"pickup\"}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
        until(() -> runs.count(
                        "select count(*) from fulfilment.return_pickups where return_id = ? and state = 'waiting'",
                        returnId)
                == 1);

        // the next pooled run takes it: the courier collects it at the door and brings it back to the shop
        jdbc.sql("update fulfilment.couriers set active = false where user_id = ?")
                .params(courier)
                .update();
        var next = flow.place(MARKET, "pooled", data.user("Cleo Ng"), "T3K 5Z9", shirt);
        runs.awaitDelivery(next.orderId());
        runs.pastCutoff(next.orderId());
        var second = runs.courierOnShift("Pickup Courier");
        runs.plan().andExpect(jsonPath("$.assigned").value(1));
        assertThat(runs.state("select state from fulfilment.return_pickups where return_id = ?", returnId))
                .isEqualTo("planned");
        runs.pack(shop, owner, next.orderId());
        runs.pickUp(second, next.orderId());
        var run = runs.run(second);
        var collect = CourierRuns.stop(run, "collect", order.orderId());
        assertThat(JsonPath.<Object>read(run, "$.stops[?(@.id == '%s')].dropoff.street".formatted(collect))
                        .toString())
                .contains("1204 17 Ave SW");
        assertThat(JsonPath.<Object>read(run, "$.stops[?(@.id == '%s')].returnRef".formatted(collect))
                        .toString())
                .contains("RT-");
        var me = TestJwt.courier(second);
        mvc.perform(post("/api/v1/courier/stops/{id}/collect", collect).with(me))
                .andExpect(status().isOk());
        until(() -> "collected".equals(runs.state("select state from orders.returns where id = ?", returnId)));
        runs.dropOff(second, CourierRuns.stop(run, "dropoff", next.orderId()), next.orderId());
        var back = ((net.minidev.json.JSONArray) JsonPath.read(
                        run, "$.stops[?(@.kind == 'return' && @.orderId == '%s')].id".formatted(order.orderId())))
                .getFirst()
                .toString();
        mvc.perform(post("/api/v1/courier/stops/{id}/returned", back).with(me)).andExpect(status().isOk());
        until(() -> "refunded".equals(runs.state("select state from orders.returns where id = ?", returnId)));
        assertThat(escrow(shirtLine)).isEqualTo("refunded");
        // the shop chose not to restock a pickup it hasn't seen
        assertThat(runs.count("select stock from catalogue.offers where id = ?", shirt.offerId()))
                .isEqualTo(8);
        // the courier was paid for the collection and the return too
        until(() ->
                runs.count("select collects from fulfilment.courier_earnings where courier_user_id = ?", second) == 1);
    }

    @Test
    void aDeclinedReturnGoesToAnAgent_andAWithdrawnOneFreesItsLine() throws Exception {
        var order = delivered("Dee Daly", shirt);
        var shirtLine = line(order, shop);
        var me = TestJwt.customer(order.customerId());
        String first = JsonPath.read(
                body(ask(order, "no_longer_needed", CourierRuns.jpeg(), shirtLine)
                        .andExpect(status().isCreated())),
                "$.id");
        mvc.perform(post("/api/v1/me/returns/{r}/withdraw", first).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("withdrawn"));
        assertThat(escrow(shirtLine)).isEqualTo("held");

        String second = JsonPath.read(
                body(ask(order, "damaged", CourierRuns.jpeg(), shirtLine).andExpect(status().isCreated())), "$.id");
        var base = "/api/v1/merchants/{m}/returns/{r}";
        mvc.perform(json(post(base + "/decline", shop, second), "{\"reason\":\" \"}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Say why the return is declined."));
        mvc.perform(json(post(base + "/decline", shop, second), "{\"reason\":\"Worn and washed\"}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("declined"))
                .andExpect(jsonPath("$.canEscalate").value(true));
        assertThat(escrow(shirtLine)).isEqualTo("held");
        mvc.perform(json(post("/api/v1/me/returns/{r}/escalate", second), "{\"statement\":\"\"}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Tell the agent why the return should be accepted."));
        mvc.perform(json(post("/api/v1/me/returns/{r}/escalate", second), "{\"statement\":\"It arrived torn.\"}")
                        .with(TestJwt.customer(data.user("Someone Else"))))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/me/returns/{r}/escalate", second), "{\"statement\":\"It arrived torn.\"}")
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("escalated"));
        // a dispute on the line's escrow: the disputes queue; the money is held again
        assertThat(jdbc.sql("""
                        select count(*) from payments.disputes d join payments.escrows e on e.id = d.ref_id
                         where e.ref_id = ?""").params(shirtLine).query(Long.class).single()).isEqualTo(1);
        assertThat(escrow(shirtLine)).isEqualTo("disputed");
        mvc.perform(json(post("/api/v1/me/returns/{r}/escalate", second), "{\"statement\":\"Again\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_declined"));
    }

    @Test
    void theReturnWindow_byCategory() throws Exception {
        var order = delivered("Eli Moss", shirt, bread);
        // groceries aren't returnable (the group's window is 0)
        ask(order, "damaged", CourierRuns.jpeg(), line(order, bakery))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_returnable"));
        // one shop at a time
        ask(order, "damaged", CourierRuns.jpeg(), line(order, bakery), line(order, shop))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Return items from one shop at a time."));
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(TestJwt.customer(order.customerId())))
                .andExpect(jsonPath("$.returnable.length()").value(1));
        // clothing: the platform's 30 days
        clock.advance(Duration.ofDays(30).plusMinutes(1));
        ask(order, "damaged", CourierRuns.jpeg(), line(order, shop))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("window_closed"))
                .andExpect(jsonPath("$.detail").value("The return window for this item has closed."));
    }
}
