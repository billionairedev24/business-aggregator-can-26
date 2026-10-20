package ca.northline.support;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.StaffRole;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import net.minidev.json.JSONArray;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-151: a courier's run driven through the real APIs (the console onboards the courier and schedules a shift, the
 * courier app runs the stops) — for the cancellation, failed-delivery, return and earnings tests, each in its own market.
 */
public final class CourierRuns {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0
    };

    /** The first bytes of a JPEG: enough for the APIs' type check. */
    public static byte[] jpeg() {
        return JPEG.clone();
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final TestData data;
    private final MovableClock clock;
    private final String market;
    private final String staff;

    public CourierRuns(MockMvc mvc, JdbcClient jdbc, TestData data, MovableClock clock, String market) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.data = data;
        this.clock = clock;
        this.market = market;
        this.staff = data.user("Dee Dispatcher");
    }

    /** The shared database keeps other tests' couriers and deliveries: the market starts empty. */
    public void emptyMarket() {
        jdbc.sql("update fulfilment.couriers set active = false where market = ?")
                .params(market)
                .update();
        jdbc.sql("update fulfilment.deliveries set state = 'cancelled' where market = ? and state = 'waiting'")
                .params(market)
                .update();
        jdbc.sql("update fulfilment.return_pickups set state = 'cancelled' where market = ? and state = 'waiting'")
                .params(market)
                .update();
        jdbc.sql("update fulfilment.runs set state = 'done' where market = ? and state not in ('done', 'cancelled')")
                .params(market)
                .update();
    }

    public static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    public String staff() {
        return staff;
    }

    /** A courier of the market on a shift that is on. Returns the courier's user id. */
    public String courierOnShift(String name) throws Exception {
        var user = data.user(name);
        var courier = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"ebike\"}".formatted(user, market))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        String courierId = JsonPath.read(courier, "$.id");
        var now = clock.instant();
        var shift = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers/{id}/shifts", courierId),
                                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                        .formatted(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(10))))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(TestJwt.courier(user)))
                .andExpect(status().isOk());
        return user;
    }

    public String courierId(String userId) {
        return jdbc.sql("select id from fulfilment.couriers where user_id = ? and market = ?")
                .params(userId, market)
                .query(String.class)
                .single();
    }

    public void awaitDelivery(String orderId) {
        until(() -> count("select count(*) from fulfilment.deliveries where order_id = ?", orderId) == 1);
    }

    /** Moves the clock past the customers' cut-off of the order's run. */
    public void pastCutoff(String orderId) {
        var orderBy = jdbc.sql("select order_by from fulfilment.deliveries where order_id = ?")
                .params(orderId)
                .query((rs, _) -> rs.getTimestamp(1).toInstant())
                .single();
        if (orderBy.isAfter(clock.instant())) {
            clock.advance(Duration.between(clock.instant(), orderBy).plusMinutes(1));
        }
    }

    public void pack(String merchantId, String owner, String orderId) throws Exception {
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", merchantId, orderId)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
        until(() -> count(
                        "select count(*) from fulfilment.delivery_pickups where order_id = ? and packed_at is not null"
                                + " and merchant_id = '" + merchantId + "'",
                        orderId)
                == 1);
    }

    public ResultActions plan() throws Exception {
        return mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{\"market\":\"%s\"}".formatted(market))
                .with(TestJwt.staff(staff, StaffRole.DISPATCH)));
    }

    /** The courier's run as the app sees it. */
    public String run(String courierUser) throws Exception {
        return body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(courierUser)))
                .andExpect(status().isOk()));
    }

    /** The id of the courier's stop of {@code kind} for the order (the first one). */
    public static String stop(String run, String kind, String orderId) {
        return ((JSONArray) JsonPath.read(
                        run, "$.stops[?(@.kind == '%s' && @.orderId == '%s')].id".formatted(kind, orderId)))
                .getFirst()
                .toString();
    }

    public static List<String> stops(String run, String kind) {
        return JsonPath.read(run, "$.stops[?(@.kind == '%s')].id".formatted(kind));
    }

    /** Arrives at and picks up every pickup of the run. */
    public void pickUpAll(String courierUser) throws Exception {
        for (var id : stops(run(courierUser), "pickup")) {
            mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", id), "{\"scanOk\":true}")
                            .with(TestJwt.courier(courierUser)))
                    .andExpect(status().isOk());
        }
    }

    /** Picks up the order at every shop of it on the courier's run. */
    public void pickUp(String courierUser, String orderId) throws Exception {
        List<String> ids = JsonPath.read(
                run(courierUser), "$.stops[?(@.kind == 'pickup' && @.orderId == '%s')].id".formatted(orderId));
        for (var id : ids) {
            mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", id), "{\"scanOk\":true}")
                            .with(TestJwt.courier(courierUser)))
                    .andExpect(status().isOk());
        }
    }

    public ResultActions photo(String courierUser, String stopId) throws Exception {
        return mvc.perform(multipart("/api/v1/courier/stops/{id}/proof", stopId)
                .file(new MockMultipartFile("file", "door.jpg", "image/jpeg", jpeg()))
                .param("kind", "photo")
                .with(TestJwt.courier(courierUser)));
    }

    public String pin(String orderId) {
        return jdbc.sql("select pin from fulfilment.deliveries where order_id = ?")
                .params(orderId)
                .query(String.class)
                .single();
    }

    /** Drops the order off with its PIN. */
    public void dropOff(String courierUser, String stopId, String orderId) throws Exception {
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/dropoff", stopId),
                                "{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(pin(orderId)))
                        .with(TestJwt.courier(courierUser)))
                .andExpect(status().isOk());
    }

    public String state(String sql, String id) {
        return jdbc.sql(sql).params(id).query(String.class).single();
    }

    public long count(String sql, String id) {
        return jdbc.sql(sql).params(id).query(Long.class).single();
    }

    public static void until(Supplier<Boolean> condition) {
        await().atMost(Duration.ofSeconds(15)).until(condition::get);
    }

    /** Waits until the order's state is {@code state} (listeners run after commit). */
    public void awaitOrder(String orderId, String state) {
        until(() -> state.equals(state("select state from orders.orders where id = ?", orderId)));
    }
}
