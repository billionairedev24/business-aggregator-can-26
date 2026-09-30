package ca.northline.orders;

import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-52: the customer's order view and its live tracking stream — only the customer's own order, the timeline driven by
 * the order's state and the shops' packing, and a new SSE event when a shop packs. Market "Trackville".
 */
class OrderTrackingApiTest extends IntegrationTest {

    static final String MARKET = "Trackville";

    @Autowired
    JdbcClient jdbc;

    String amara;
    String bakery;
    String butcher;
    String bakeryOwner;
    String orderId;
    String windowId;

    @BeforeEach
    void order() throws Exception {
        shopFixtures.categories();
        amara = data.user("Amara Osei");
        bakery = shopFixtures.shop(MARKET, "Glenmore Bakery", "master");
        butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher", "trusted");
        bakeryOwner = data.user("Glenmore Owner");
        data.member(bakery, bakeryOwner, MerchantRole.OWNER);
        var landing = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(get("/api/v1/public/shop").param("market", MARKET))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        windowId = landing.path("run").path("windowId").asString();
        orderId = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, window_id, subtotal_cents, delivery_fee_cents,
                               service_fee_cents, tax_cents, tip_cents, delivery_kind, fulfilment_mode)
                        values (?, ?, ?, 'goods', 'placed', ?, 3350, 299, 0, 182, 0, 'pooled', 'delivery')
                        """)
                .params(orderId, "NL-9" + orderId.substring(20), amara, windowId)
                .update();
        var bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 750, 10);
        for (var line : new Object[][] {{bakery, bread.offerId(), 2, 750}, {butcher, null, 1, 1850}}) {
            jdbc.sql("""
                            insert into orders.order_lines (id, order_id, merchant_id, offer_id, qty, unit_cents, state, title)
                            values (?, ?, ?, ?, ?, ?, 'pending', 'Item')
                            """)
                    .params(Ids.next(), orderId, line[0], line[1], line[2], line[3])
                    .update();
        }
    }

    @Test
    void theCustomerSeesTheirOrderAndItsTimeline() throws Exception {
        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("placed"))
                .andExpect(jsonPath("$.totalCents").value(3350 + 299 + 182))
                .andExpect(jsonPath("$.delivery.kind").value("pooled"))
                .andExpect(jsonPath("$.delivery.runLabel").exists())
                .andExpect(jsonPath("$.delivery.startsAt").exists())
                .andExpect(jsonPath("$.shops.length()").value(2))
                .andExpect(jsonPath("$.shops[0].name").value("Glenmore Bakery"))
                .andExpect(jsonPath("$.shops[0].items").value(2))
                .andExpect(jsonPath("$.shops[0].packed").value(false))
                .andExpect(jsonPath("$.steps[0].key").value("paid"))
                .andExpect(jsonPath("$.steps[0].state").value("done"))
                .andExpect(jsonPath("$.steps[1].state").value("current"))
                .andExpect(jsonPath("$.steps[2].state").value("todo"))
                .andExpect(jsonPath("$.steps[3].state").value("todo"));
    }

    @Test
    void onlyTheCustomerCanSeeIt() throws Exception {
        mvc.perform(get("/api/v1/me/orders/{id}", orderId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(data.user("Someone else"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/orders/{id}/events", orderId).with(TestJwt.customer(data.user("Someone else"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/orders/{id}", Ids.next()).with(TestJwt.customer(amara)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theTimelineFollowsTheOrderState() throws Exception {
        for (var step : new String[][] {{"ready", "2"}, {"picked_up", "3"}}) {
            jdbc.sql("update orders.orders set state = ? where id = ?")
                    .params(step[0], orderId)
                    .update();
            mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.steps[" + step[1] + "].state").value("current"))
                    .andExpect(jsonPath("$.steps[" + (Integer.parseInt(step[1]) - 1) + "].state")
                            .value("done"));
        }
        jdbc.sql("update orders.orders set state = 'delivered', delivered_at = now() where id = ?")
                .params(orderId)
                .update();
        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(amara)))
                .andExpect(jsonPath("$.steps[3].state").value("done"))
                .andExpect(jsonPath("$.deliveredAt").exists());
    }

    @Test
    void theStreamSendsTheOrderNowAndAgainWhenAShopPacks() throws Exception {
        var stream = mvc.perform(get("/api/v1/me/orders/{id}/events", orderId).with(TestJwt.customer(amara)))
                .andExpect(request().asyncStarted())
                .andReturn();
        var response = stream.getResponse();
        assertThat(response.getContentAsString()).contains("event:order").contains("\"state\":\"placed\"");

        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", bakery, orderId)
                        .with(TestJwt.member(bakeryOwner)))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"packed\":true"));
        assertThat(response.getContentAsString().split("event:order").length - 1)
                .isGreaterThanOrEqualTo(2);
    }
}
