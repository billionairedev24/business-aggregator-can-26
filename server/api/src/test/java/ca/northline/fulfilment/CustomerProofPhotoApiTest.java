package ca.northline.fulfilment;

import static ca.northline.support.ShopFixtures.BAKERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Mobile gaps part 1: the customer's delivered screen shows the courier's door photo through {@code GET
 * /me/orders/{id}/proof-photo} — a 5-minute signed URL from the proof storage port (under {@code test} the api's own
 * signed link), only for the customer's own order (403 for anyone else's, 404 for no such order or no photo: a PIN
 * drop-off). The link itself works without a token, refuses a forged or expired token, and is never cached.
 */
@Import(MovableClock.Config.class)
class CustomerProofPhotoApiTest extends IntegrationTest {

    static final String MARKET = "Photoville";
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    ShopOrderFlow flow;
    String bakery;
    String bakeryOwner;
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
        bakery = shopFixtures.shop(MARKET, "Photo Bakery", "master");
        bakeryOwner = data.user("Pia Baker");
        data.member(bakery, bakeryOwner, MerchantRole.OWNER);
        bread = shopFixtures.listing(bakery, BAKERY, "Rye loaf", 650, 20);
        flow = new ShopOrderFlow(mvc, jdbc, data);
        staff = data.user("Dot Dispatcher");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    String stopOf(String run, String kind, String orderId) {
        return ((JSONArray) JsonPath.read(
                        run, "$.stops[?(@.kind == '%s' && @.orderId == '%s')].id".formatted(kind, orderId)))
                .getFirst()
                .toString();
    }

    @Test
    void theCustomerSeesTheDoorPhoto_throughAShortLivedLink_andNobodyElse() throws Exception {
        var amara = data.user("Amara Photo");
        var ben = data.user("Ben Pin");
        var a = flow.place(MARKET, "pooled", amara, "T2T 0B8", bread);
        var b = flow.place(MARKET, "pooled", ben, "T2A 1A1", bread);
        for (var id : new String[] {a.orderId(), b.orderId()}) {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> jdbc.sql("select count(*) from fulfilment.deliveries where order_id = ?")
                                    .params(id)
                                    .query(Long.class)
                                    .single()
                            == 1);
        }
        // before the delivery: nothing to show
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.customer(amara)))
                .andExpect(status().isNotFound());

        var orderBy = jdbc.sql("select order_by from fulfilment.deliveries where order_id = ?")
                .params(a.orderId())
                .query((rs, _) -> rs.getTimestamp(1).toInstant())
                .single();
        clock.advance(Duration.between(clock.instant(), orderBy).plusMinutes(1));
        var courier = data.user("Cam Courier");
        var created = body(mvc.perform(post("/api/v1/console/fulfilment/couriers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"ebike\"}"
                                .formatted(courier, MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        var now = clock.instant();
        var shift = body(mvc.perform(
                        post("/api/v1/console/fulfilment/couriers/{id}/shifts", JsonPath.<String>read(created, "$.id"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                        .formatted(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(6))))
                                .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isCreated()));
        var me = TestJwt.courier(courier);
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(me))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/console/fulfilment/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"market\":\"%s\"}".formatted(MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk());
        var run = body(mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isOk()));
        for (var id : new String[] {a.orderId(), b.orderId()}) {
            mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", bakery, id)
                            .with(TestJwt.member(bakeryOwner)))
                    .andExpect(status().isOk());
        }
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                        select count(*) from fulfilment.delivery_pickups
                                         where order_id in (?, ?) and packed_at is not null""")
                                .params(a.orderId(), b.orderId())
                                .query(Long.class)
                                .single()
                        == 2);
        for (var stop : (JSONArray) JsonPath.read(run, "$.stops[?(@.kind == 'pickup')].id")) {
            mvc.perform(post("/api/v1/courier/stops/{id}/pickup", stop)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"scanOk\":true}")
                            .with(me))
                    .andExpect(status().isOk());
        }
        var dropA = stopOf(run, "dropoff", a.orderId());
        mvc.perform(multipart("/api/v1/courier/stops/{id}/proof", dropA)
                        .file(new MockMultipartFile("file", "door.jpg", "image/jpeg", JPEG))
                        .param("kind", "photo")
                        .with(me))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/courier/stops/{id}/dropoff", dropA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"photo\"}")
                        .with(me))
                .andExpect(status().isOk());
        var pin = jdbc.sql("select pin from fulfilment.deliveries where order_id = ?")
                .params(b.orderId())
                .query(String.class)
                .single();
        mvc.perform(post("/api/v1/courier/stops/{id}/dropoff", stopOf(run, "dropoff", b.orderId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(pin))
                        .with(me))
                .andExpect(status().isOk());

        // the customer: a signed link, good for 5 minutes, that serves the photo without a token
        var asked = clock.instant();
        var answer = body(mvc.perform(
                        get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.customer(amara)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").isNotEmpty()));
        String url = JsonPath.read(answer, "$.url");
        assertThat(Instant.parse(JsonPath.read(answer, "$.expiresAt")))
                .isBetween(asked.plus(Duration.ofMinutes(5)), clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(url).startsWith("/api/v1/dev/proof-photos/").doesNotContain(a.orderId());
        var bytes = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(bytes).isEqualTo(JPEG);
        // the same link through the app's DPoP-bound token
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.consumerApp(amara)))
                .andExpect(status().isOk());

        // a delivery with an ID check at the door (age-restricted items) never shows its photo, whatever was uploaded
        jdbc.sql("update fulfilment.deliveries set id_check_age = 19 where order_id = ?")
                .params(a.orderId())
                .update();
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.customer(amara)))
                .andExpect(status().isNotFound());
        jdbc.sql("update fulfilment.deliveries set id_check_age = null where order_id = ?")
                .params(a.orderId())
                .update();

        // anyone else: 403 for another customer's order (also the courier and the shop), 401 signed out
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.customer(ben)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This order isn't yours."));
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.courier(courier)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId()).with(TestJwt.member(bakeryOwner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", a.orderId())).andExpect(status().isUnauthorized());
        // a PIN drop-off has no photo; no such order
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", b.orderId()).with(TestJwt.customer(ben)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/orders/{id}/proof-photo", "01JNOSUCHORDER0000000000000")
                        .with(TestJwt.customer(amara)))
                .andExpect(status().isNotFound());

        // a forged link, another key in a link, an expired link: refused
        var parts = url.substring(url.lastIndexOf('/') + 1).split("\\.");
        mvc.perform(get("/api/v1/dev/proof-photos/{t}", parts[0] + "." + parts[1] + ".AAAA"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dev/proof-photos/{t}", "Zm9v." + parts[1] + "." + parts[2]))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dev/proof-photos/{t}", "not-a-token")).andExpect(status().isForbidden());
        clock.advance(Duration.ofMinutes(6));
        mvc.perform(get(url))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This link has expired. Open the order again."));
    }
}
