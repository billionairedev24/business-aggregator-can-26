package ca.northline.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.orders.api.OrderPacked;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.OperationsFixtures.Line;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Orders · products: packing list, "Mark packed", nav badge. */
@RecordApplicationEvents
class OrdersApiTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    List<NavBadgeContributor> badges;

    @Test
    void packingListShowsOnlyMyLines_markPackedMovesToAwaitingPickup() throws Exception {
        var fx = new OperationsFixtures(jdbc);
        var seller = data.merchant("seller", "Prairie Wrench Parts");
        var owner = data.user("Ravi");
        data.member(seller, owner, MerchantRole.OWNER);
        var otherSeller = data.merchant("seller", "Other shop");
        var customer = data.user("Amara Osei");
        var tonight = fx.window(Duration.ofHours(3), "R-611");
        var multi = fx.order(
                tonight,
                customer,
                "placed",
                new Line(seller, "Wiper blades", 2, 1995, "pending"),
                new Line(otherSeller, "Floor mats", 1, 5000, "pending"));
        fx.order(tonight, customer, "ready", new Line(seller, "Brake pads (rear)", 1, 7140, "packed"));

        mvc.perform(get("/api/v1/merchants/{m}/orders", seller).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(multi))
                .andExpect(jsonPath("$.items[0].status").value("to_pack"))
                .andExpect(jsonPath("$.items[0].customerName").value("A. Osei"))
                .andExpect(jsonPath("$.items[0].lines", hasSize(1)))
                .andExpect(jsonPath("$.items[0].totalCents").value(3990))
                .andExpect(jsonPath("$.items[0].runLabel").value("R-611"))
                .andExpect(jsonPath("$.items[1].status").value("awaiting_pickup"))
                .andExpect(jsonPath("$.counts.toPack").value(1))
                .andExpect(jsonPath("$.counts.awaitingPickup").value(1))
                .andExpect(jsonPath("$.counts.nextRunLabel").value("R-611"));

        var all = new HashMap<String, String>();
        badges.forEach(b ->
                all.putAll(b.badges(new NavBadgeContributor.Context(seller, "u", MerchantRole.OWNER, Locale.CANADA))));
        assertThat(all).containsEntry("orders", "1 to pack");
        var fr = new HashMap<String, String>();
        badges.forEach(b -> fr.putAll(
                b.badges(new NavBadgeContributor.Context(seller, "u", MerchantRole.OWNER, Locale.CANADA_FRENCH))));
        assertThat(fr).containsEntry("orders", "1 à emballer");

        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", seller, multi).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("awaiting_pickup"))
                .andExpect(jsonPath("$.lines[0].state").value("packed"));
        assertThat(events.stream(OrderPacked.class)).singleElement().satisfies(e -> {
            assertThat(e.orderState()).isEqualTo("packing"); // the other seller still has to pack
            assertThat(e.linesPacked()).isEqualTo(1);
        });
        assertThat(jdbc.sql("select state from orders.order_lines where order_id = ? and merchant_id = ?")
                        .params(multi, otherSeller)
                        .query(String.class)
                        .single())
                .isEqualTo("pending");

        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", seller, multi).with(TestJwt.member(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_state"));
    }

    @Test
    void lastSellerToPackMakesTheOrderReady() throws Exception {
        var fx = new OperationsFixtures(jdbc);
        var biz = data.business(MerchantRole.OWNER);
        var order = fx.order(
                fx.window(Duration.ofHours(2), "R-700"),
                data.user("C"),
                "accepted",
                new Line(biz.merchantId(), "Oil", 1, 4410, "pending"));
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", biz.merchantId(), order)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select state from orders.orders where id = ?")
                        .param(order)
                        .query(String.class)
                        .single())
                .isEqualTo("ready");
    }

    @Test
    void authorization() throws Exception {
        var fx = new OperationsFixtures(jdbc);
        var biz = data.business(MerchantRole.OWNER);
        var bookkeeper = data.user("Priya");
        data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
        var order = fx.order(
                fx.window(Duration.ofHours(2), "R-701"),
                data.user("C"),
                "placed",
                new Line(biz.merchantId(), "Oil", 1, 4410, "pending"));

        mvc.perform(get("/api/v1/merchants/{m}/orders", biz.merchantId()).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", biz.merchantId(), order)
                        .with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get("/api/v1/merchants/{m}/orders", biz.merchantId()).with(TestJwt.member(data.user("X"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/merchants/{m}/orders", biz.merchantId()).with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden());
        var other = data.business(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/merchants/{m}/orders/{o}", other.merchantId(), order)
                        .with(TestJwt.member(other.userId())))
                .andExpect(status().isNotFound());
    }
}
