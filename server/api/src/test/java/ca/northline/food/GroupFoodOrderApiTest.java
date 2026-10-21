package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.KitchenFixtures.Kitchen;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.region.api.Markets;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S-159 group food orders end to end with the fake gateway: start, share the code, guests add dishes, the host locks;
 * the host pays for everyone (S-57's checkout) or each person pays a share (a card payment and escrow each, released
 * on the kitchen's hand-off); one kitchen ticket either way; cancelling releases the paid shares; who may do what, the
 * 404s, 403s, 409s and the 422 messages. Each test has its own kitchen and city.
 */
@RecordApplicationEvents
class GroupFoodOrderApiTest extends IntegrationTest {

    private static final String ALL_DAY = "[[\"00:00\",\"23:59\"]]";
    private static final double KITCHEN_LAT = 50.0;
    private static final double KITCHEN_LNG = -100.0;
    private static final double NEAR_LAT = 50.0117;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    Markets markets;

    KitchenFixtures fx;
    Kitchen k;
    String pho;
    String rolls;
    String large;
    String host;
    String guest;
    String guest2;

    @BeforeEach
    void kitchen() throws Exception {
        assumeThat(LocalTime.now(markets.zone("AB"))).isBefore(LocalTime.of(23, 50));
        fx = new KitchenFixtures(jdbc, data);
        k = fx.kitchen();
        var city = "Groupville " + Ids.next().substring(18);
        jdbc.sql("update merchants.merchants set city = ?, province = 'AB' where id = ?")
                .params(city, k.merchantId())
                .update();
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, published_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', now())
                        """)
                .params(Ids.next(), k.merchantId(), "grp-" + k.merchantId().toLowerCase(Locale.ROOT))
                .update();
        jdbc.sql("""
                        insert into merchants.locations (merchant_id, geom, service_radius_km, source)
                        values (?, ST_GeogFromText(?), 8, 'manual')
                        """)
                .params(k.merchantId(), "POINT(%s %s)".formatted(KITCHEN_LNG, KITCHEN_LAT))
                .update();
        jdbc.sql("""
                        insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, fulfilment,
                               group_orders, group_max)
                        values (?, 20, 6, '{courier,pickup,scheduled}', true, 3)
                        """).params(k.merchantId()).update();
        for (int day = 1; day <= 7; day++) {
            jdbc.sql("insert into food.opening_hours (merchant_id, weekday, ranges) values (?, ?, cast(? as jsonb))")
                    .params(k.merchantId(), day, ALL_DAY)
                    .update();
        }
        var menu = fx.menu(k, "live");
        pho = fx.item(k, menu.mainsId(), "Pho tai", 1600, 5);
        rolls = fx.item(k, menu.mainsId(), "Spring rolls", 800, 0);
        var group = mvc.perform(post(k.base() + "/modifier-groups")
                        .with(TestJwt.member(k.ownerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Size","pickRule":"exactly","pickCount":1,"required":true,
                                 "options":[{"name":"Regular"},{"name":"Large","priceDeltaCents":300}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        large = JsonPath.read(group, "$.options[1].id");
        jdbc.sql("insert into food.item_modifiers (item_id, group_id, sort) values (?, ?, 0)")
                .params(pho, JsonPath.<String>read(group, "$.id"))
                .update();
        host = data.user("Hana Host");
        guest = data.user("Gil Guest");
        guest2 = data.user("Gwen Guest");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static RequestPostProcessor as(String userId) {
        return TestJwt.customerWithMfa(userId);
    }

    String start(String payment) throws Exception {
        var body = mvc.perform(json(post("/api/v1/me/group-orders"), """
                                {"merchantId":"%s","mode":"delivery","payment":"%s",
                                 "delivery":{"street":"12 Test Ave","unit":"Apt 4","city":"Groupville","zone":"Beltline",
                                   "province":"AB","postalCode":"T0T 0T0","lat":%s,"lng":%s,"dropoff":"door",
                                   "extras":[]}}""".formatted(
                                        k.merchantId(), payment, NEAR_LAT, KITCHEN_LNG))
                        .with(as(host)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("open"))
                .andExpect(jsonPath("$.host").value(true))
                .andExpect(jsonPath("$.area").value("Beltline"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    String code(String groupId) throws Exception {
        return JsonPath.read(
                mvc.perform(get("/api/v1/me/group-orders/{id}", groupId).with(as(host)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.code");
    }

    ResultActions join(String userId, String code) throws Exception {
        return mvc.perform(json(post("/api/v1/me/group-orders/join"), "{\"code\":\"%s\"}".formatted(code))
                .with(as(userId)));
    }

    ResultActions add(String userId, String groupId, String itemJson) throws Exception {
        return mvc.perform(json(post("/api/v1/me/group-orders/{id}/items", groupId), itemJson)
                .with(as(userId)));
    }

    String largePho() {
        return "{\"itemId\":\"%s\",\"qty\":1,\"optionIds\":[\"%s\"],\"note\":\"extra basil\"}".formatted(pho, large);
    }

    String twoRolls() {
        return "{\"itemId\":\"%s\",\"qty\":2}".formatted(rolls);
    }

    ResultActions lock(String userId, String groupId) throws Exception {
        return mvc.perform(json(
                        post("/api/v1/me/group-orders/{id}/lock", groupId),
                        "{\"tip\":{\"kind\":\"amount\",\"value\":500}}")
                .with(as(userId)));
    }

    ResultActions pay(RequestPostProcessor auth, String groupId, String forUser) throws Exception {
        return mvc.perform(post("/api/v1/me/group-orders/{id}/shares/{u}/pay", groupId, forUser)
                .with(auth)
                .header("Idempotency-Key", Ids.next()));
    }

    ResultActions confirmShare(String payer, String groupId, String forUser) throws Exception {
        return mvc.perform(post("/api/v1/me/group-orders/{id}/shares/{u}/confirm", groupId, forUser)
                .with(as(payer))
                .header("Idempotency-Key", Ids.next()));
    }

    /** A group with the host's two rolls and the guest's large pho, the guest joined by the typed code. */
    String filled(String payment) throws Exception {
        var id = start(payment);
        var typed = code(id).toLowerCase(Locale.ROOT).replace("-", " ");
        join(guest, typed).andExpect(status().isOk()).andExpect(jsonPath("$.members", hasSize(2)));
        add(host, id, twoRolls()).andExpect(status().isOk());
        add(guest, id, largePho())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[1].items[0].title").value("Pho tai"))
                .andExpect(jsonPath("$.members[1].items[0].choices[0]").value("Large"))
                .andExpect(jsonPath("$.members[1].subtotalCents").value(1900))
                .andExpect(jsonPath("$.subtotalCents").value(3500));
        return id;
    }

    // ── the host pays for everyone ──────────────────────────────────────────────────────────────────────────

    @Test
    void theHostPaysForEveryone_andTheKitchenGetsOneTicket() throws Exception {
        var id = filled("host");
        lock(guest, id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_host"));
        lock(host, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("locked"));
        add(guest, id, twoRolls())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("group_locked"));

        var started = mvc.perform(json(post("/api/v1/me/group-orders/{id}/checkout", id), "{}")
                        .with(as(host))
                        .header("Idempotency-Key", Ids.next()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.subtotalCents").value(3500))
                .andExpect(jsonPath("$.totals.tipCents").value(500))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String orderId = JsonPath.read(started, "$.orderId");
        mvc.perform(post("/api/v1/me/group-orders/{id}/confirm", id)
                        .with(as(host))
                        .header("Idempotency-Key", Ids.next()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("placed"))
                .andExpect(jsonPath("$.orderId").value(orderId));

        assertThat(jdbc.sql("select group_order_id from orders.orders where id = ?")
                        .params(orderId)
                        .query(String.class)
                        .single())
                .isEqualTo(id);
        assertThat(events.stream(OrderPlaced.class).filter(e -> e.aggregateId().equals(orderId)))
                .hasSize(1);
        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(get(k.base() + "/kitchen/live").with(cook))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].groupSize").value(2))
                .andExpect(jsonPath("$.items[0].lines", hasSize(2)));
        // the guest follows the one order; nobody cancels it once the kitchen has it
        mvc.perform(get("/api/v1/me/group-orders/{id}", id).with(as(guest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value(false))
                .andExpect(jsonPath("$.tracking.stage").value("paid"));
        mvc.perform(post("/api/v1/me/group-orders/{id}/cancel", id).with(as(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_cancellable"));
    }

    // ── each person pays their share ────────────────────────────────────────────────────────────────────────

    @Test
    void eachPersonPaysAShare_theyAddUp_andTheHandOffReleasesThemAll() throws Exception {
        var id = filled("split");
        join(guest2, code(id)).andExpect(status().isOk());
        add(guest2, id, twoRolls()).andExpect(status().isOk());
        var locked = lock(host, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].share.tipCents").value(500))
                .andExpect(jsonPath("$.members[1].share.tipCents").value(0))
                .andExpect(jsonPath("$.members[1].share.state").value("due"))
                .andExpect(jsonPath("$.canPlace").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> totals = JsonPath.read(locked, "$.members[*].share.totalCents");
        List<Integer> subtotals = JsonPath.read(locked, "$.members[*].share.subtotalCents");
        List<Integer> deliveries = JsonPath.read(locked, "$.members[*].share.deliveryFeeCents");
        assertThat(subtotals).containsExactly(1600, 1900, 1600);
        assertThat(deliveries.stream().mapToInt(Integer::intValue).sum()).isEqualTo(299); // 1–2 km, shared
        var sum = totals.stream().mapToInt(Integer::intValue).sum();

        // a guest pays only their own share; the host may pay anyone's
        pay(as(guest), id, guest2)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_your_share"));
        pay(as(guest), id, guest)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents").value(totals.get(1)));
        confirmShare(guest, id, guest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[1].share.state").value("authorized"));
        mvc.perform(post("/api/v1/me/group-orders/{id}/place", id).with(as(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("shares_unpaid"));
        pay(as(host), id, host).andExpect(status().isCreated());
        confirmShare(host, id, host).andExpect(status().isOk());
        pay(as(host), id, guest2).andExpect(status().isCreated());
        confirmShare(host, id, guest2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[2].share.paidBy").value("H. Host"))
                .andExpect(jsonPath("$.canPlace").value(true));
        mvc.perform(post("/api/v1/me/group-orders/{id}/unlock", id).with(as(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("shares_paid"));

        var placed = mvc.perform(post("/api/v1/me/group-orders/{id}/place", id).with(as(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("placed"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String orderId = JsonPath.read(placed, "$.orderId");
        var row = jdbc.sql("select subtotal_cents, total_cents, tip_cents from orders.food_checkouts where id = ?")
                .params(orderId)
                .query()
                .singleRow();
        assertThat(row).containsEntry("subtotal_cents", 5100L).containsEntry("tip_cents", 500L);
        assertThat(((Number) row.get("total_cents")).intValue()).isEqualTo(sum);
        var escrows = jdbc.sql("""
                        select e.ref_id from payments.escrows e
                          join orders.group_order_shares s on s.id = e.ref_id
                         where e.ref_type = 'food_order' and s.group_order_id = ?""").params(id).query(String.class).list();
        assertThat(escrows).hasSize(3).contains(orderId);
        assertThat(events.stream(OrderPlaced.class).filter(e -> e.aggregateId().equals(orderId)))
                .hasSize(1);

        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", orderId).with(cook))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("""
                                        select count(*) from payments.escrows e
                                          join orders.group_order_shares s on s.id = e.ref_id
                                         where e.ref_type = 'food_order' and s.group_order_id = ?
                                           and e.release_at is not null""").params(id).query(Integer.class).single())
                        .isEqualTo(3));
    }

    @Test
    void cancellingBeforeTheKitchenHasItReleasesThePaidShares() throws Exception {
        var id = filled("split");
        lock(host, id).andExpect(status().isOk());
        pay(as(guest), id, guest).andExpect(status().isCreated());
        confirmShare(guest, id, guest).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/group-orders/{id}/cancel", id).with(as(guest)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/group-orders/{id}/cancel", id).with(as(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"))
                .andExpect(jsonPath("$.members[1].share.state").value("voided"));
        assertThat(jdbc.sql("""
                                select e.state from payments.escrows e
                                  join orders.group_order_shares s on s.id = e.ref_id
                                 where e.ref_type = 'food_order' and s.group_order_id = ? and s.user_id = ?""").params(id, guest).query(String.class).single()).isEqualTo("refunded");
        join(guest2, code(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("group_closed"));
    }

    // ── rules ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void onlyMembersSeeAGroup_andCodesAreChecked() throws Exception {
        var id = start("host");
        mvc.perform(get("/api/v1/me/group-orders/{id}", id).with(as(guest))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/group-orders/join/{c}", code(id)).with(as(guest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostFirstName").value("Hana"))
                .andExpect(jsonPath("$.people").value(1));
        join(guest, "7Q2M-K4X")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter the code you were sent, like 7Q2M-K4XP."));
        join(guest, "2222-2222").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/group-orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void theGroupIsLimitedToTheKitchensSize_andGuestsLeaveButTheHostCancels() throws Exception {
        var id = start("host");
        var code = code(id);
        join(guest, code).andExpect(status().isOk());
        join(guest, code).andExpect(status().isOk()).andExpect(jsonPath("$.members", hasSize(2)));
        join(guest2, code).andExpect(status().isOk());
        join(data.user("Fourth Friend"), code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("group_full"));
        add(guest2, id, twoRolls()).andExpect(status().isOk());
        var itemId = JsonPath.<String>read(
                add(guest, id, largePho()).andReturn().getResponse().getContentAsString(), "$.members[1].items[0].id");
        mvc.perform(delete("/api/v1/me/group-orders/{id}/items/{i}", id, itemId).with(as(guest2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_yours"));
        mvc.perform(post("/api/v1/me/group-orders/{id}/leave", id).with(as(guest2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.subtotalCents").value(1900));
        mvc.perform(post("/api/v1/me/group-orders/{id}/leave", id).with(as(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("host_cannot_leave"));
        mvc.perform(get("/api/v1/me/group-orders").with(as(guest)))
                .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(id), hasSize(1)));
    }

    @Test
    void startingAndLockingAreValidated() throws Exception {
        mvc.perform(json(
                                post("/api/v1/me/group-orders"),
                                "{\"merchantId\":\"%s\",\"mode\":\"pickup\"}".formatted(k.merchantId()))
                        .with(as(host)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("payment"))
                .andExpect(
                        jsonPath("$.errors[0].message").value("Choose who pays: you, or each person their own share."));
        mvc.perform(json(
                                post("/api/v1/me/group-orders"),
                                "{\"merchantId\":\"%s\",\"mode\":\"pickup\",\"payment\":\"split\"}"
                                        .formatted(k.merchantId()))
                        .with(as(host))
                        .header("Accept-Language", "fr-CA"))
                .andExpect(status().isCreated());
        var id = start("split");
        lock(host, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add something to the order before you lock it."));
        join(guest, code(id));
        add(guest, id, largePho());
        lock(host, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[0].message").value("To split the bill, add something for yourself first."));
        jdbc.sql("update food.kitchen_settings set group_orders = false where merchant_id = ?")
                .params(k.merchantId())
                .update();
        mvc.perform(json(
                                post("/api/v1/me/group-orders"),
                                "{\"merchantId\":\"%s\",\"mode\":\"pickup\",\"payment\":\"host\"}"
                                        .formatted(k.merchantId()))
                        .with(as(host)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("group_orders_off"));
    }

    @Test
    void aPhoneCodeSignInStepsUpBeforePayingAShare() throws Exception {
        var id = filled("split");
        lock(host, id).andExpect(status().isOk());
        pay(TestJwt.customer(guest), id, guest)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("second_factor_required"));
        jdbc.sql("update identity.users set mfa_primary = 'passkey' where id = ?")
                .params(guest)
                .update();
        pay(TestJwt.customer(guest), id, guest)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("step_up_required"));
        mvc.perform(post("/api/v1/me/group-orders/{id}/shares/{u}/pay", id, guest)
                        .with(TestJwt.customer(guest))
                        .header("Idempotency-Key", Ids.next())
                        .header("X-Step-Up", "dev"))
                .andExpect(status().isCreated());
    }
}
