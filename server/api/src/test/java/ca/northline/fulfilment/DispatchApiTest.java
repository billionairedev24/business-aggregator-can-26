package ca.northline.fulfilment;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.api.DeliveryAssigned;
import ca.northline.fulfilment.api.DeliveryCompleted;
import ca.northline.fulfilment.api.DeliveryPickedUp;
import ca.northline.fulfilment.api.RunPlanned;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-86 end to end in market "Dispatchville": orders placed through the real checkout reach fulfilment
 * ({@code DeliveryRequests} from orders' listeners), tonight's run is planned once the customers' cut-off passes, the
 * stops are ordered (pickups first; drop-offs by postal code without coordinates), a courier on shift is assigned and
 * runs the stops through the courier API — pickup needs the shop's packing, drop-off a photo / signature / PIN —
 * which delivers the order (S-78) — and the console's view and actions. The application clock is moved.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class DispatchApiTest extends IntegrationTest {

    static final String MARKET = "Dispatchville";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    ApplicationEvents events;

    ShopOrderFlow flow;
    String bakery;
    String butcher;
    String bakeryOwner;
    String butcherOwner;
    Listing bread;
    Listing steak;
    String staff;

    @BeforeEach
    void market() {
        clock.reset();
        // the shared database keeps other tests' couriers and deliveries: this market starts empty
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
        butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher", "trusted");
        bakeryOwner = data.user("Bea Baker");
        butcherOwner = data.user("Bo Butcher");
        data.member(bakery, bakeryOwner, MerchantRole.OWNER);
        data.member(butcher, butcherOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 750, 20);
        steak = shopFixtures.listing(butcher, BUTCHER, "Ribeye", 1850, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
        staff = data.user("Dee Dispatcher");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** A courier of the market (console), on a shift that is on (courier app). Returns the courier's user id. */
    String courierOnShift(String name) throws Exception {
        var user = data.user(name);
        var courier = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"ebike\"}".formatted(user, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("offline")));
        String courierId = JsonPath.read(courier, "$.id");
        var now = clock.instant();
        var shift = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers/{id}/shifts", courierId),
                                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                        .formatted(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(6))))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(TestJwt.courier(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("on"));
        return user;
    }

    void awaitDelivery(String orderId) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select count(*) from fulfilment.deliveries where order_id = ?")
                                .params(orderId)
                                .query(Long.class)
                                .single()
                        == 1);
    }

    /** Moves the clock past the customers' cut-off of the order's run. */
    void pastCutoff(String orderId) {
        var orderBy = jdbc.sql("select order_by from fulfilment.deliveries where order_id = ?")
                .params(orderId)
                .query((rs, _) -> rs.getTimestamp(1).toInstant())
                .single();
        clock.advance(Duration.between(clock.instant(), orderBy).plusMinutes(1));
    }

    void pack(String merchantId, String owner, String orderId) throws Exception {
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", merchantId, orderId)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
    }

    void awaitPacked(String orderId, int shops) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                        select count(*) from fulfilment.delivery_pickups
                                         where order_id = ? and packed_at is not null""").params(orderId).query(Long.class).single() == shops);
    }

    ResultActions plan() throws Exception {
        return mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{\"market\":\"%s\"}".formatted(MARKET))
                .with(TestJwt.staff(staff, StaffRole.DISPATCH)));
    }

    String pin(String orderId) {
        return jdbc.sql("select pin from fulfilment.deliveries where order_id = ?")
                .params(orderId)
                .query(String.class)
                .single();
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void tonightsRunIsCreatedFromOrdersWithItsStopsOrdered() throws Exception {
        var a = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread, steak);
        var b = flow.place(MARKET, "pooled", data.user("Ben Okafor"), "T2A 1A1", bread);
        var c = flow.place(MARKET, "pooled", data.user("Cleo Ng"), "T3K 5Z9", steak);
        assertThat(b.windowId()).isEqualTo(a.windowId());
        List.of(a, b, c).forEach(o -> awaitDelivery(o.orderId()));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select count(*) from fulfilment.delivery_pickups where order_id = ?")
                                .params(a.orderId())
                                .query(Long.class)
                                .single()
                        == 2);

        // before the customers' cut-off nothing is planned
        plan().andExpect(status().isOk()).andExpect(jsonPath("$.runs").value(0));
        pastCutoff(a.orderId());
        plan().andExpect(status().isOk()).andExpect(jsonPath("$.runs").value(1));
        plan().andExpect(jsonPath("$.runs").value(0)); // planned once

        var runs = body(mvc.perform(get("/api/v1/console/fulfilment/runs")
                        .param("market", MARKET)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk()));
        List<Map<String, Object>> mine = JsonPath.read(runs, "$.items[?(@.orders == 3)]");
        assertThat(mine).hasSize(1);
        var runId = (String) mine.getFirst().get("id");
        assertThat(mine.getFirst())
                .containsEntry("kind", "pooled")
                .containsEntry("state", "planned")
                .containsEntry("stopsTotal", 7)
                .containsEntry("heuristic", "nearest-neighbour-v1");
        assertThat(mine.getFirst().get("label")).isNotNull();

        var detail = body(mvc.perform(get("/api/v1/console/fulfilment/runs/{id}", runId)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk()));
        List<String> kinds = JsonPath.read(detail, "$.stops[*].kind");
        assertThat(kinds).containsExactly("pickup", "pickup", "pickup", "pickup", "dropoff", "dropoff", "dropoff");
        // drop-offs without coordinates by postal code: T2A, T2T, T3K
        List<String> drops = JsonPath.read(detail, "$.stops[?(@.kind == 'dropoff')].orderId");
        assertThat(drops).containsExactly(b.orderId(), a.orderId(), c.orderId());
        // pickups grouped by shop, each shop's orders together; ETAs never go back in time
        List<String> etas = JsonPath.read(detail, "$.stops[*].eta");
        assertThat(etas).isSortedAccordingTo(java.util.Comparator.comparing(Instant::parse));
        assertThat(events.stream(RunPlanned.class).filter(e -> e.aggregateId().equals(runId)))
                .singleElement()
                .satisfies(
                        e -> assertThat(e.orderIds()).containsExactlyInAnyOrder(a.orderId(), b.orderId(), c.orderId()));

        // the order's delivery in the console; the shops' pack-by time in the Studio
        mvc.perform(get("/api/v1/console/fulfilment/orders/{id}", a.orderId())
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("planned"))
                .andExpect(jsonPath("$.run.id").value(runId))
                .andExpect(jsonPath("$.pickups.length()").value(2))
                .andExpect(jsonPath("$.packBy").isNotEmpty());
        mvc.perform(get("/api/v1/merchants/{m}/orders/{o}", bakery, a.orderId()).with(TestJwt.member(bakeryOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoffAt").isNotEmpty())
                .andExpect(jsonPath("$.courierPickup.courierAssigned").value(false))
                .andExpect(jsonPath("$.courierPickup.eta").isNotEmpty());
    }

    @Test
    void aCourierOnShiftRunsThePickupsAndDropOffsWithProof() throws Exception {
        var a = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread, steak);
        var b = flow.place(MARKET, "pooled", data.user("Ben Okafor"), "T2A 1A1", bread);
        awaitDelivery(a.orderId());
        awaitDelivery(b.orderId());
        pastCutoff(a.orderId());
        var courier = courierOnShift("Kai Courier");
        var me = TestJwt.courier(courier);
        mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isNoContent());
        plan().andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").value(1))
                .andExpect(jsonPath("$.assigned").value(1));
        assertThat(events.stream(DeliveryAssigned.class))
                .anyMatch(e -> e.orderIds().contains(a.orderId()));

        var run = body(mvc.perform(get("/api/v1/courier/run").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("planned"))
                .andExpect(jsonPath("$.stops.length()").value(5))
                .andExpect(jsonPath("$.stops[0].place.name").isNotEmpty())
                .andExpect(jsonPath("$.stops[3].dropoff.street").value("1204 17 Ave SW"))
                .andExpect(jsonPath("$.stops[3].dropoff.note").value("Buzz 0804")));
        String bakeryA = ((JSONArray) JsonPath.read(
                        run,
                        "$.stops[?(@.kind == 'pickup' && @.orderId == '%s' && @.place.merchantId == '%s')].id"
                                .formatted(a.orderId(), bakery)))
                .getFirst()
                .toString();
        String dropA = ((JSONArray) JsonPath.read(
                        run, "$.stops[?(@.kind == 'dropoff' && @.orderId == '%s')].id".formatted(a.orderId())))
                .getFirst()
                .toString();
        String dropB = ((JSONArray) JsonPath.read(
                        run, "$.stops[?(@.kind == 'dropoff' && @.orderId == '%s')].id".formatted(b.orderId())))
                .getFirst()
                .toString();

        // the shop hasn't packed: no pickup; a drop-off before the pickups can't happen
        mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", bakeryA), "{\"scanOk\":true}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_packed"))
                .andExpect(jsonPath("$.detail").value("This shop hasn't packed the order yet."));
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", dropA), "{\"proof\":\"pin\",\"pin\":\"0000\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_picked_up"));

        pack(bakery, bakeryOwner, a.orderId());
        pack(butcher, butcherOwner, a.orderId());
        pack(bakery, bakeryOwner, b.orderId());
        awaitPacked(a.orderId(), 2);
        awaitPacked(b.orderId(), 1);
        mvc.perform(post("/api/v1/courier/stops/{id}/arrive", bakeryA).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("loading"));
        // the Studio sees the courier waiting
        mvc.perform(get("/api/v1/merchants/{m}/orders/{o}", bakery, a.orderId()).with(TestJwt.member(bakeryOwner)))
                .andExpect(jsonPath("$.courierPickup.courierAssigned").value(true))
                .andExpect(jsonPath("$.courierPickup.arrivedAt").isNotEmpty());
        var afterPickups = "";
        for (var stop : (JSONArray) JsonPath.read(run, "$.stops[?(@.kind == 'pickup')].id")) {
            afterPickups = body(mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", stop), "{\"scanOk\":true}")
                            .with(me))
                    .andExpect(status().isOk()));
        }
        assertThat(JsonPath.<String>read(afterPickups, "$.state")).isEqualTo("en_route");
        assertThat(events.stream(DeliveryPickedUp.class).map(DeliveryPickedUp::aggregateId))
                .contains(a.orderId(), b.orderId());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select state from orders.orders where id = ?")
                        .params(a.orderId())
                        .query(String.class)
                        .single()
                        .equals("picked_up"));

        // B: the PIN — wrong, then right
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", dropB), "{\"proof\":\"pin\"}")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter the customer's 4-digit PIN."));
        var wrong = pin(b.orderId()).equals("1234") ? "4321" : "1234";
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/dropoff", dropB),
                                "{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(wrong))
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("That PIN doesn't match. Ask the customer for the 4 digits on their order."));
        mvc.perform(json(
                                post("/api/v1/courier/stops/{id}/dropoff", dropB),
                                "{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(pin(b.orderId())))
                        .with(me))
                .andExpect(status().isOk());

        // A: a photo — not before it's uploaded, not a file that isn't an image
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", dropA), "{\"proof\":\"photo\"}")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("proof_missing"));
        mvc.perform(multipart("/api/v1/courier/stops/{id}/proof", dropA)
                        .file(new MockMultipartFile(
                                "file",
                                "x.jpg",
                                "image/jpeg",
                                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("kind", "photo")
                        .with(me))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Upload a JPG, PNG or WebP image under 5 MB."));
        mvc.perform(multipart("/api/v1/courier/stops/{id}/proof", dropA)
                        .file(new MockMultipartFile("file", "door.jpg", "image/jpeg", JPEG))
                        .param("kind", "photo")
                        .with(me))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", dropA), "{\"proof\":\"photo\"}")
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"));
        assertThat(events.stream(DeliveryCompleted.class)
                        .filter(e -> e.aggregateId().equals(a.orderId())))
                .singleElement()
                .satisfies(e -> assertThat(e.proof()).isEqualTo("photo"));

        // S-78: the order is delivered and the goods escrow's 7-day window started
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select state from orders.orders where id = ?")
                        .params(a.orderId())
                        .query(String.class)
                        .single()
                        .equals("delivered"));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                select count(*) from payments.escrows
                                 where ref_type = 'order_line' and ref_id in (:ids) and release_at is not null""")
                                .param("ids", a.lineIds())
                                .query(Long.class)
                                .single()
                        == 2);
        // the run is done: the courier is free, then off shift
        mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isNoContent());
        // S-87: the app replays actions whose answers it lost; on the done run they are no-ops
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", dropA), "{\"proof\":\"photo\"}")
                        .with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"));
        mvc.perform(post("/api/v1/courier/stops/{id}/arrive", dropB).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"));
        mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", bakeryA), "{\"scanOk\":true}")
                        .with(me))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/courier/stops/{id}/proof", dropA)
                        .file(new MockMultipartFile("file", "door.jpg", "image/jpeg", JPEG))
                        .param("kind", "photo")
                        .with(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stop_done"));
        assertThat(events.stream(DeliveryCompleted.class)
                        .filter(e -> e.aggregateId().equals(a.orderId())))
                .hasSize(1);
        mvc.perform(get("/api/v1/courier/me").with(me))
                .andExpect(jsonPath("$.status").value("available"));
        var shiftId =
                JsonPath.<String>read(body(mvc.perform(get("/api/v1/courier/me").with(me))), "$.shift.id");
        mvc.perform(post("/api/v1/courier/shifts/{id}/end", shiftId).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"));
        mvc.perform(get("/api/v1/courier/me").with(me))
                .andExpect(jsonPath("$.status").value("offline"));
    }

    @Test
    void theCourierApiIsForCouriersWithKeyBoundTokens_andTheirOwnStopsOnly() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread);
        awaitDelivery(order.orderId());
        pastCutoff(order.orderId());
        var kai = courierOnShift("Kai Courier");
        var lee = courierOnShift("Lee Courier");
        plan().andExpect(jsonPath("$.assigned").value(1));
        var holder = jdbc.sql("""
                        select c.user_id from fulfilment.runs r join fulfilment.couriers c on c.id = r.courier_id
                         join fulfilment.deliveries d on d.run_id = r.id where d.order_id = ?""").params(order.orderId()).query(String.class).single();
        var other = holder.equals(kai) ? lee : kai;
        var stop = JsonPath.<String>read(
                body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(holder)))), "$.stops[0].id");

        mvc.perform(get("/api/v1/courier/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/courier/me").with(TestJwt.customer(holder))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courier/me").with(TestJwt.courierBearer(holder)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courier/me").with(TestJwt.courier(data.user("Not A Courier"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_a_courier"));
        // another courier's stop doesn't exist for them
        mvc.perform(post("/api/v1/courier/stops/{id}/arrive", stop).with(TestJwt.courier(other)))
                .andExpect(status().isNotFound());
        // a shift with an open run can't end
        var shift = JsonPath.<String>read(
                body(mvc.perform(get("/api/v1/courier/me").with(TestJwt.courier(holder)))), "$.shift.id");
        mvc.perform(post("/api/v1/courier/shifts/{id}/end", shift).with(TestJwt.courier(holder)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Finish your run before ending the shift."));
        // a drop-off without a proof kind
        mvc.perform(json(post("/api/v1/courier/stops/{id}/dropoff", stop), "{}").with(TestJwt.courier(holder)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose photo, signature or PIN as proof."));
    }

    @Test
    void theConsoleIsForStaffWithASecondFactor_andChecksItsInput() throws Exception {
        mvc.perform(get("/api/v1/console/fulfilment/runs").with(TestJwt.customer(data.user("C"))))
                .andExpect(status().isForbidden());
        // S-90 console roles: runs and couriers are the delivery screen (dispatch); support sees an order's delivery
        // on the orders screen but can't dispatch; analysts see neither
        mvc.perform(get("/api/v1/console/fulfilment/runs").with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/fulfilment/orders/{id}", Ids.next())
                        .with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/console/fulfilment/orders/{id}", Ids.next())
                        .with(TestJwt.staff(staff, StaffRole.ANALYST)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{}").with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/fulfilment/runs").with(TestJwt.staffWithoutMfa(staff, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        mvc.perform(get("/api/v1/console/fulfilment/runs").with(TestJwt.courier(data.user("K"))))
                .andExpect(status().isForbidden());
        var user = data.user("Vic Vehicle");
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"rocket\"}".formatted(user, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose bike, ebike, car or van."));
        var courier = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"car\"}".formatted(user, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"car\"}".formatted(user, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_a_courier"));
        var now = clock.instant();
        mvc.perform(json(
                                post(
                                        "/api/v1/console/fulfilment/couriers/{id}/shifts",
                                        JsonPath.<String>read(courier, "$.id")),
                                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                        .formatted(now, now.plus(Duration.ofHours(13))))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("A shift ends after it starts and lasts at most 12 hours."));
        mvc.perform(get("/api/v1/console/fulfilment/couriers")
                        .param("market", MARKET)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.userId == '%s')].name".formatted(user))
                        .value("Vic Vehicle"));
        mvc.perform(get("/api/v1/console/fulfilment/orders/{id}", Ids.next())
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRunCanBeGivenToAnotherFreeCourierUntilItStarts() throws Exception {
        var order = flow.place(MARKET, "pooled", data.user("Amara Osei"), "T2T 0B8", bread);
        awaitDelivery(order.orderId());
        pastCutoff(order.orderId());
        var kai = courierOnShift("Kai Courier");
        plan().andExpect(jsonPath("$.assigned").value(1));
        var runId = jdbc.sql("select run_id from fulfilment.deliveries where order_id = ?")
                .params(order.orderId())
                .query(String.class)
                .single();
        var lee = courierOnShift("Lee Courier");
        var leeId = jdbc.sql("select id from fulfilment.couriers where user_id = ?")
                .params(lee)
                .query(String.class)
                .single();
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/runs/{id}/assign", runId),
                                "{\"courierId\":\"%s\"}".formatted(leeId))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courier.userId").value(lee))
                .andExpect(jsonPath("$.courier.name").value("Lee Courier"));
        // the reassignment is in the platform audit log (no business), with the dispatcher's role
        assertThat(jdbc.sql("""
                                select action, role, merchant_id is null as platform from developer.audit_log
                                 where target_id = ? and action = 'fulfilment.run_assigned'""").params(runId).query().singleRow())
                .containsEntry("role", "dispatch")
                .containsEntry("platform", true);
        mvc.perform(get("/api/v1/courier/me").with(TestJwt.courier(kai)))
                .andExpect(jsonPath("$.status").value("available"));
        mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(kai))).andExpect(status().isNoContent());
        // once Lee starts, the run stays Lee's
        var stop = JsonPath.<String>read(
                body(mvc.perform(get("/api/v1/courier/run").with(TestJwt.courier(lee)))), "$.stops[0].id");
        mvc.perform(post("/api/v1/courier/stops/{id}/arrive", stop).with(TestJwt.courier(lee)))
                .andExpect(status().isOk());
        var kaiId = jdbc.sql("select id from fulfilment.couriers where user_id = ?")
                .params(kai)
                .query(String.class)
                .single();
        mvc.perform(json(
                                post("/api/v1/console/fulfilment/runs/{id}/assign", runId),
                                "{\"courierId\":\"%s\"}".formatted(kaiId))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("run_started"));
    }

    @Test
    void placedOrdersOfOtherKindsAreHandedOverToo() throws Exception {
        var direct = flow.place(MARKET, "direct", data.user("Dana Direct"), "T2T 0B8", bread);
        awaitDelivery(direct.orderId());
        assertThat(jdbc.sql("select kind, order_type, state from fulfilment.deliveries where order_id = ?")
                        .params(direct.orderId())
                        .query()
                        .singleRow())
                .containsEntry("kind", "direct")
                .containsEntry("order_type", "goods")
                .containsEntry("state", "waiting");
        // a direct order is planned once its shop has packed
        plan().andExpect(jsonPath("$.runs").value(0));
        pack(bakery, bakeryOwner, direct.orderId());
        awaitPacked(direct.orderId(), 1);
        plan().andExpect(jsonPath("$.runs").value(1));
        mvc.perform(get("/api/v1/console/fulfilment/orders/{id}", direct.orderId())
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(jsonPath("$.run.kind").value("direct"))
                .andExpect(jsonPath("$.run.stopsTotal").value(2));
    }
}
