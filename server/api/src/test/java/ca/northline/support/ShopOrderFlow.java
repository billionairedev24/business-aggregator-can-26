package ca.northline.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.support.ShopFixtures.Listing;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A shop order placed the way a customer places it (S-51 cart → checkout → place, fake payment gateway): escrow held
 * per line, the delivery fee authorized. For the fulfilment tests (S-78, S-86, S-88), each in its own test market.
 */
public final class ShopOrderFlow {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final TestData data;

    public ShopOrderFlow(MockMvc mvc, JdbcClient jdbc, TestData data) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.data = data;
    }

    /** @param windowId the pooled run's window; empty for the direct courier */
    public record Placed(String orderId, String customerId, String windowId, List<String> lineIds) {}

    /** One of each listing, on the first pooled run, by a new customer. */
    public Placed pooled(String market, Listing... listings) throws Exception {
        return place(market, "pooled", data.user("Amara Osei"), listings);
    }

    /** @param kind {@code pooled} (the market's first open run) | {@code direct} */
    public Placed place(String market, String kind, String customer, Listing... listings) throws Exception {
        var auth = TestJwt.customerWithMfa(customer);
        for (var l : listings) {
            mvc.perform(post("/api/v1/cart/items")
                            .with(auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"offerId\":\"%s\",\"qty\":1}".formatted(l.offerId())))
                    .andExpect(status().isCreated());
        }
        var setup = json(mvc.perform(get("/api/v1/me/checkout").param("market", market).with(auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String windowId = "";
        for (var option : setup.path("options")) {
            if (option.path("kind").asString().equals(kind)) {
                windowId = option.path("windowId").isNull() ? "" : option.path("windowId").asString();
                break;
            }
        }
        var address = """
                {"street":"1204 17 Ave SW","unit":"Apt 804","city":"%s","province":"AB","postal":"t2t0b8","note":"Buzz 0804"}"""
                .formatted(market);
        var body = kind.equals("pooled")
                ? """
                        {"kind":"pooled","windowId":"%s","address":%s,"substitution":"similar"}"""
                        .formatted(windowId, address)
                : """
                        {"kind":"direct","address":%s,"substitution":"similar"}""".formatted(address);
        var started = json(mvc.perform(post("/api/v1/me/checkouts")
                        .with(auth)
                        .header("Idempotency-Key", Ids.next())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var placed = json(mvc.perform(post("/api/v1/me/checkouts/{id}/place", started.path("checkoutId").asString())
                        .with(auth)
                        .header("Idempotency-Key", Ids.next()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var orderId = placed.path("orderId").asString();
        var lines = jdbc.sql("select id from orders.order_lines where order_id = ? order by id")
                .params(orderId)
                .query(String.class)
                .list();
        return new Placed(orderId, customer, windowId, lines);
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
