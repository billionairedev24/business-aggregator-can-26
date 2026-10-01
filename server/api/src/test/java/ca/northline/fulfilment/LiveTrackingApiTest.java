package ca.northline.fulfilment;

import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.ShopOrderFlow;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
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
 * S-88 in market "Pingville": the courier's app sends its position (rate-limited, on shift only); the customer whose
 * order the courier carries sees the courier's first name, position, ETA and their drop-off PIN — on the order and on
 * its live stream, which pushes every move — and nothing of the position before pickup or after delivery. Positions
 * never reach Postgres. The application clock is moved.
 */
@Import(MovableClock.Config.class)
class LiveTrackingApiTest extends IntegrationTest {

    static final String MARKET = "Pingville";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    ShopOrderFlow flow;
    String bakery;
    String owner;
    Listing bread;
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
        owner = data.user("Bea Baker");
        data.member(bakery, owner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 750, 20);
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

    ResultActions ping(String courier, double lat, double lng) throws Exception {
        return mvc.perform(json(post("/api/v1/courier/location"), "{\"lat\":%s,\"lng\":%s}".formatted(lat, lng))
                .with(TestJwt.courier(courier)));
    }

    /** An order on its way: placed, planned, packed, picked up by a courier on shift. Returns {order, customer, courier}. */
    List<String> onItsWay() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread);
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
        // off shift, the app's position isn't taken
        ping(courier, 50.0, -100.0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_on_shift"));
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(TestJwt.courier(courier)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{\"market\":\"%s\"}".formatted(MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(jsonPath("$.assigned").value(1));

        // planned, not picked up: the customer sees the courier coming and the PIN, not where the courier is
        ping(courier, 50.0, -100.0).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/orders/{id}", order.orderId()).with(TestJwt.customer(order.customerId())))
                .andExpect(jsonPath("$.courier.state").value("planned"))
                .andExpect(jsonPath("$.courier.courierName").value("Kai"))
                .andExpect(jsonPath("$.courier.pin").value(org.hamcrest.Matchers.matchesPattern("\\d{4}")))
                .andExpect(jsonPath("$.courier.lat").doesNotExist());

        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", bakery, order.orderId())
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10))
                .until(() ->
                        jdbc.sql("""
                                        select count(*) from fulfilment.delivery_pickups
                                         where order_id = ? and packed_at is not null""").params(order.orderId()).query(Long.class).single() == 1);
        var run = body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(courier))));
        var pickup = ((JSONArray) JsonPath.read(run, "$.stops[?(@.kind == 'pickup')].id"))
                .getFirst()
                .toString();
        mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", pickup), "{\"scanOk\":true}")
                        .with(TestJwt.courier(courier)))
                .andExpect(status().isOk());
        return List.of(order.orderId(), order.customerId(), courier);
    }

    @Test
    void theCustomerSeesTheCourierMoveAndTheEtaOnTheOrderAndItsStream() throws Exception {
        var it = onItsWay();
        var orderId = it.get(0);
        var customer = TestJwt.customer(it.get(1));
        var courier = it.get(2);

        var stream = mvc.perform(get("/api/v1/me/orders/{id}/events", orderId).with(customer))
                .andExpect(request().asyncStarted())
                .andReturn();
        var response = stream.getResponse();
        assertThat(response.getContentAsString()).contains("event:order");

        clock.advance(Duration.ofSeconds(3));
        ping(courier, 50.0447, -100.0719)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAfterMs").value(2000));
        // faster than every 2 s is refused, with when to retry
        ping(courier, 50.0448, -100.0719)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("too_many_pings"));
        // the stream pushes the move
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"lat\":50.0447"));

        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(customer))
                .andExpect(jsonPath("$.state").value("picked_up"))
                .andExpect(jsonPath("$.courier.state").value("picked_up"))
                .andExpect(jsonPath("$.courier.courierName").value("Kai"))
                .andExpect(jsonPath("$.courier.lat").value(50.0447))
                .andExpect(jsonPath("$.courier.lng").value(-100.0719))
                .andExpect(jsonPath("$.courier.positionAt").isNotEmpty())
                .andExpect(jsonPath("$.courier.eta").isNotEmpty())
                .andExpect(jsonPath("$.courier.stopsBefore").value(0));
        // two seconds later the next position is taken and pushed again
        clock.advance(Duration.ofSeconds(2));
        ping(courier, 50.05, -100.07).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"lat\":50.05,"));

        // someone else's order shows nothing
        mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(TestJwt.customer(data.user("Other"))))
                .andExpect(status().isNotFound());

        // delivered: no position, no PIN
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
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> mvc.perform(get("/api/v1/me/orders/{id}", orderId).with(customer))
                                .andExpect(jsonPath("$.state").value("delivered"))
                                .andExpect(jsonPath("$.courier.state").value("delivered"))
                                .andExpect(jsonPath("$.courier.lat").doesNotExist())
                                .andExpect(jsonPath("$.courier.pin").doesNotExist()));
    }

    @Test
    void positionsAreCheckedAndNeverStoredInPostgres() throws Exception {
        var it = onItsWay();
        clock.advance(Duration.ofSeconds(3));
        ping(it.get(2), 200, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Send a latitude and longitude on the map."));
        mvc.perform(json(post("/api/v1/courier/location"), "{\"lat\":50.0}").with(TestJwt.courier(it.get(2))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("lng"));
        ping(it.get(2), 50.0447, -100.0719).andExpect(status().isOk());
        // the ops map sees the courier's latest position
        mvc.perform(get("/api/v1/console/fulfilment/couriers")
                        .param("market", MARKET)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(jsonPath("$.items[?(@.userId == '%s')].position.lat".formatted(it.get(2)))
                        .value(50.0447));
        // no table or column of the fulfilment schema holds coordinates of couriers
        assertThat(jdbc.sql("""
                                select count(*) from information_schema.columns
                                 where table_schema = 'fulfilment' and column_name in ('lat', 'lng', 'position', 'geom')""").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                                select count(*) from information_schema.tables
                                 where table_schema = 'fulfilment' and table_name like '%position%'""").query(Long.class).single()).isZero();
        // a courier who isn't one can't send positions
        ping(data.user("Nobody"), 50, -100).andExpect(status().isForbidden());
    }
}
