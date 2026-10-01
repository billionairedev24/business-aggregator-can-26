package ca.northline.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.food.KitchenFixtures.Kitchen;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.region.api.Markets;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Locale;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S-57 end to end with the fake gateway: the food landing and restaurant reads, the pick rules and validation messages,
 * quote → pay (S-51's step-up rule, Idempotency-Key) → confirm (one escrow per food order with Northline's charges,
 * S-51's {@code order.placed}) → tracking that follows the kitchen display (accept, ready, hand-off), and the escrow
 * release on hand-off. Each test runs in its own city; places and coordinates are test data.
 */
@RecordApplicationEvents
class FoodOrderingApiTest extends IntegrationTest {

    private static final String ALL_DAY = "[[\"00:00\",\"23:59\"]]";
    private static final double KITCHEN_LAT = 50.0;
    private static final double KITCHEN_LNG = -100.0;
    /** ≈ 1.3 km north of the kitchen: the 1–2 km delivery fee. */
    private static final double NEAR_LAT = 50.0117;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    Markets markets;

    @Autowired
    ca.northline.fulfilment.application.DispatchUseCases.PlanRuns plan;

    KitchenFixtures fx;
    Kitchen k;
    String city;
    String slug;
    String pho;
    String rolls;
    String sizeGroup;
    String regular;
    String large;
    String customer;

    @BeforeEach
    void kitchen() throws Exception {
        // "00:00"–"23:59" leaves one minute a day closed, in the kitchen's market time zone
        assumeThat(LocalTime.now(markets.zone("AB"))).isBefore(LocalTime.of(23, 50));
        fx = new KitchenFixtures(jdbc, data);
        k = fx.kitchen();
        city = "Foodville " + Ids.next().substring(18);
        slug = "pho-" + k.merchantId().toLowerCase(Locale.ROOT);
        jdbc.sql("""
                        update merchants.merchants set city = ?, province = 'AB',
                               profile = '{"cuisines":["vietnamese"],"dietary":["halal"],"kitchenAddress":"1 Test St"}'::jsonb
                         where id = ?
                        """).params(city, k.merchantId()).update();
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, published_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', now())
                        """).params(Ids.next(), k.merchantId(), slug).update();
        jdbc.sql("""
                        insert into merchants.locations (merchant_id, geom, service_radius_km, source)
                        values (?, ST_GeogFromText(?), 8, 'manual')
                        """)
                .params(k.merchantId(), "POINT(%s %s)".formatted(KITCHEN_LNG, KITCHEN_LAT))
                .update();
        jdbc.sql("""
                        insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, fulfilment)
                        values (?, 20, 6, '{courier,pickup,scheduled}')
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
        sizeGroup = JsonPath.read(group, "$.id");
        regular = JsonPath.read(group, "$.options[0].id");
        large = JsonPath.read(group, "$.options[1].id");
        jdbc.sql("insert into food.item_modifiers (item_id, group_id, sort) values (?, ?, 0)")
                .params(pho, sizeGroup)
                .update();
        customer = data.user("Amara Osei");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String delivery(double lat) {
        return """
                {"street":"12 Test Ave","unit":"Apt 4","province":"AB","postalCode":"T0T 0T0","lat":%s,"lng":%s,
                 "dropoff":"door","note":"Buzz 4","extras":["utensils"]}""".formatted(lat, KITCHEN_LNG);
    }

    String order(String mode, String items, @Nullable String delivery, String extra) {
        return """
                {"merchantId":"%s","mode":"%s","items":%s,"combos":[],"tip":{"kind":"amount","value":400},
                 "delivery":%s%s}""".formatted(k.merchantId(), mode, items, delivery == null ? "null" : delivery, extra);
    }

    String twoLargePho() {
        return "[{\"itemId\":\"%s\",\"qty\":2,\"optionIds\":[\"%s\"],\"note\":\"no onions\"}]".formatted(pho, large);
    }

    ResultActions start(RequestPostProcessor auth, @Nullable String proof, String body, @Nullable String key)
            throws Exception {
        var request = json(post("/api/v1/me/food-orders"), body).with(auth);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (proof != null) {
            request.header("X-Step-Up", proof);
        }
        return mvc.perform(request);
    }

    String startDelivery() throws Exception {
        var body = start(
                        TestJwt.customerWithMfa(customer),
                        null,
                        order("delivery", twoLargePho(), delivery(NEAR_LAT), ""),
                        Ids.next())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.orderId");
    }

    ResultActions confirm(String orderId, RequestPostProcessor auth) throws Exception {
        return mvc.perform(
                post("/api/v1/me/food-orders/{id}/confirm", orderId).with(auth).header("Idempotency-Key", Ids.next()));
    }

    // ── public reads ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theLandingListsTheCitysKitchensWithOpenStateDistanceAndFees() throws Exception {
        mvc.perform(get("/api/v1/public/kitchens")
                        .param("city", city)
                        .param("lat", String.valueOf(NEAR_LAT))
                        .param("lng", String.valueOf(KITCHEN_LNG)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, public"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].slug").value(slug))
                .andExpect(jsonPath("$.items[0].open").value(true))
                .andExpect(jsonPath("$.items[0].cuisines[0]").value("vietnamese"))
                .andExpect(jsonPath("$.items[0].delivers").value(true))
                .andExpect(jsonPath("$.items[0].distanceKm").value(1.3))
                .andExpect(jsonPath("$.items[0].deliveryFeeCents").value(299))
                .andExpect(jsonPath("$.items[0].priceLevel").value("$$")); // dishes average $12
        // far outside the kitchen's 8 km
        mvc.perform(get("/api/v1/public/kitchens")
                        .param("city", city)
                        .param("lat", "50.2")
                        .param("lng", String.valueOf(KITCHEN_LNG)))
                .andExpect(jsonPath("$.items[0].delivers").value(false));
        // paused in the Studio
        jdbc.sql("update food.kitchen_settings set paused_until = ? where merchant_id = ?")
                .params(JdbcTimes.ts(Instant.now().plus(Duration.ofMinutes(20))), k.merchantId())
                .update();
        mvc.perform(get("/api/v1/public/kitchens").param("city", city))
                .andExpect(jsonPath("$.items[0].open").value(false))
                .andExpect(jsonPath("$.items[0].paused").value(true));
        mvc.perform(get("/api/v1/public/kitchens").param("city", " "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a city."));
        mvc.perform(get("/api/v1/public/kitchens").param("city", city).param("lat", "91"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Latitude must be between -90 and 90."));
    }

    @Test
    void theRestaurantShowsItsOrderableMenuWithPickRulesTaxAndWindows() throws Exception {
        mvc.perform(get("/api/v1/public/kitchens/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchen.name").value("Pho Test"))
                .andExpect(jsonPath("$.address").value("1 Test St"))
                .andExpect(jsonPath("$.minOrderCents").value(1500))
                .andExpect(jsonPath("$.serviceFeeBps").value(800))
                .andExpect(jsonPath("$.taxBps").value(500))
                .andExpect(jsonPath("$.sections[0].items[0].name").value("Pho tai"))
                .andExpect(jsonPath("$.sections[0].items[0].groups[0].rule").value("exactly"))
                .andExpect(jsonPath("$.sections[0].items[0].groups[0].required").value(true))
                .andExpect(jsonPath("$.sections[0].items[0].groups[0].options[1].deltaCents")
                        .value(300))
                .andExpect(jsonPath("$.slots").isNotEmpty());
        mvc.perform(get("/api/v1/public/kitchens/{slug}", "nobody-" + Ids.next().toLowerCase(Locale.ROOT)))
                .andExpect(status().isNotFound());
    }

    // ── quote and validation ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theQuotePricesFromTheLiveMenuWithFeesTipAndTax() throws Exception {
        var body = mvc.perform(json(
                                post("/api/v1/me/food-orders/quote"),
                                order("delivery", twoLargePho(), delivery(NEAR_LAT), ""))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitCents").value(1900))
                .andExpect(jsonPath("$.lines[0].choices[0]").value("Large"))
                .andExpect(jsonPath("$.subtotalCents").value(3800))
                .andExpect(jsonPath("$.deliveryFeeCents").value(299))
                .andExpect(jsonPath("$.serviceFeeCents").value(304))
                .andExpect(jsonPath("$.tipCents").value(400))
                .andExpect(jsonPath("$.feeTaxCents").value(30))
                .andExpect(jsonPath("$.estimate").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int sub = JsonPath.read(body, "$.subtotalCents");
        int fee = JsonPath.read(body, "$.deliveryFeeCents");
        int service = JsonPath.read(body, "$.serviceFeeCents");
        int tip = JsonPath.read(body, "$.tipCents");
        int tax = JsonPath.read(body, "$.taxCents");
        int total = JsonPath.read(body, "$.totalCents");
        assertThat(total).isEqualTo(sub + fee + service + tip + tax);
        // pickup: no delivery fee, no tip
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("pickup", twoLargePho(), null, ""))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryFeeCents").value(0))
                .andExpect(jsonPath("$.tipCents").value(0));
    }

    @Test
    void ordersAreCheckedWithTheirMessages() throws Exception {
        var auth = TestJwt.customer(customer);
        var noSize = "[{\"itemId\":\"%s\",\"qty\":1,\"optionIds\":[]}]".formatted(pho);
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("delivery", noSize, delivery(NEAR_LAT), ""))
                        .with(auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Pick 1 for Size."));
        var oneRoll = "[{\"itemId\":\"%s\",\"qty\":1,\"optionIds\":[]}]".formatted(rolls);
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("pickup", oneRoll, null, ""))
                        .with(auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Add $7.00 to reach the $15 minimum."));
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("delivery", twoLargePho(), null, ""))
                        .with(auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Add your delivery address first."));
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("drone", twoLargePho(), null, ""))
                        .with(auth))
                .andExpect(jsonPath("$.errors[0].message").value("Choose delivery or pickup."));
        var bigTip =
                order("delivery", twoLargePho(), delivery(NEAR_LAT), "").replace("\"value\":400", "\"value\":20000");
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), bigTip).with(auth))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a tip between $0 and $100, or up to 30 %."));
        var pastWindow =
                order("delivery", twoLargePho(), delivery(NEAR_LAT), ",\"scheduledFor\":\"2020-01-01T00:00:00Z\"");
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), pastWindow).with(auth))
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of the windows offered."));
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("delivery", twoLargePho(), delivery(50.2), ""))
                        .with(auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("out_of_range"));
        jdbc.sql("update food.kitchen_settings set paused_until = ? where merchant_id = ?")
                .params(JdbcTimes.ts(Instant.now().plus(Duration.ofMinutes(20))), k.merchantId())
                .update();
        mvc.perform(json(post("/api/v1/me/food-orders/quote"), order("pickup", twoLargePho(), null, ""))
                        .with(auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("kitchen_closed"));
    }

    // ── paying ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aPhoneCodeSignInStepsUpFirstAsForTheShop() throws Exception {
        var body = order("delivery", twoLargePho(), delivery(NEAR_LAT), "");
        start(TestJwt.customer(customer), null, body, Ids.next())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("second_factor_required"));
        jdbc.sql("update identity.users set mfa_primary = 'passkey' where id = ?")
                .params(customer)
                .update();
        start(TestJwt.customer(customer), null, body, Ids.next())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("step_up_required"));
        start(TestJwt.customer(customer), "dev", body, Ids.next()).andExpect(status().isCreated());
    }

    @Test
    void payingNeedsAnIdempotencyKeyAndReplaysItsAnswer() throws Exception {
        var body = order("delivery", twoLargePho(), delivery(NEAR_LAT), "");
        start(TestJwt.customerWithMfa(customer), null, body, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        var key = Ids.next();
        var first = start(TestJwt.customerWithMfa(customer), null, body, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("fake"))
                .andExpect(jsonPath("$.status").value("authorized"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        start(TestJwt.customerWithMfa(customer), null, body, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(first));
        start(TestJwt.customerWithMfa(customer), null, body.replace("\"qty\":2", "\"qty\":3"), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertThat(jdbc.sql("select count(*) from orders.food_checkouts where customer_id = ?")
                        .params(customer)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void confirmingHoldsOneEscrowWithNorthlinesChargesAndPublishesOrderPlaced() throws Exception {
        var orderId = startDelivery();
        confirm(orderId, TestJwt.customerWithMfa(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.ref").isNotEmpty());
        // a second confirm (a retried tap) changes nothing
        confirm(orderId, TestJwt.customerWithMfa(customer)).andExpect(status().isOk());

        var order = jdbc.sql("select type, state, fulfilment_mode, customer_id from orders.orders where id = ?")
                .params(orderId)
                .query()
                .singleRow();
        assertThat(order)
                .containsEntry("type", "food")
                .containsEntry("state", "placed")
                .containsEntry("fulfilment_mode", "delivery")
                .containsEntry("customer_id", customer);
        var escrow = jdbc.sql("""
                        select platform_fee_cents, platform_tax_cents, tip_cents, state
                          from payments.escrows where ref_type = 'food_order' and ref_id = ?
                        """).params(orderId).query().singleRow();
        assertThat(escrow)
                .containsEntry("platform_fee_cents", 299L + 304L)
                .containsEntry("platform_tax_cents", 30L)
                .containsEntry("tip_cents", 400L)
                .containsEntry("state", "held");
        assertThat(events.stream(OrderPlaced.class).filter(e -> e.aggregateId().equals(orderId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.orderType()).isEqualTo("food");
                    assertThat(e.delivery()).isEqualTo("direct");
                    assertThat(e.merchantId()).isEqualTo(k.merchantId());
                    assertThat(e.subtotalCents()).isEqualTo(3800);
                    assertThat(e.lines()).singleElement().satisfies(l -> {
                        assertThat(l.itemKind()).isEqualTo("menu_item");
                        assertThat(l.qty()).isEqualTo(2);
                    });
                });
        // someone else's order doesn't exist for them
        confirm(orderId, TestJwt.customerWithMfa(data.user("Someone Else"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/food-orders/{id}", orderId).with(TestJwt.customer(data.user("Other"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void trackingFollowsTheKitchenDisplayAndHandOffReleasesTheEscrow() throws Exception {
        var orderId = startDelivery();
        confirm(orderId, TestJwt.customerWithMfa(customer)).andExpect(status().isOk());
        var me = TestJwt.customer(customer);
        mvc.perform(get("/api/v1/me/food-orders/{id}", orderId).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("paid"))
                .andExpect(jsonPath("$.mode").value("delivery"))
                .andExpect(jsonPath("$.lines[0].title").value("Pho tai"));

        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(get(k.base() + "/kitchen/live").with(cook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lines[0].modifiers[0]").value("Large"));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/food-orders/{id}", orderId).with(me))
                .andExpect(jsonPath("$.stage").value("cooking"))
                .andExpect(jsonPath("$.prepMin").isNumber())
                .andExpect(jsonPath("$.eta").isNotEmpty());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/food-orders/{id}", orderId).with(me))
                .andExpect(jsonPath("$.stage").value("ready"));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", orderId).with(cook))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> mvc.perform(
                                get("/api/v1/me/food-orders/{id}", orderId).with(me))
                        .andExpect(jsonPath("$.stage").value("on_the_way")));
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.sql(
                                        "select release_at is not null from payments.escrows where ref_type = 'food_order' and ref_id = ?")
                                .params(orderId)
                                .query(Boolean.class)
                                .single())
                        .isTrue());
    }

    /** S-89: pickup checkout writes the mode and the customer's arrival time; the kitchen hands it to the customer. */
    @Test
    void pickupSetsTheModeAndTheCustomersArrivalAndTheKitchenHandsItToTheCustomer() throws Exception {
        var body = start(TestJwt.customerWithMfa(customer), null, order("pickup", twoLargePho(), null, ""), Ids.next())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String orderId = JsonPath.read(body, "$.orderId");
        confirm(orderId, TestJwt.customerWithMfa(customer)).andExpect(status().isOk());

        var order = jdbc.sql("""
                        select fulfilment_mode, delivery_fee_cents,
                               customer_eta > placed_at and customer_eta < placed_at + interval '2 hours' as eta_ahead
                          from orders.orders where id = ?
                        """).params(orderId).query().singleRow();
        assertThat(order)
                .containsEntry("fulfilment_mode", "pickup")
                .containsEntry("delivery_fee_cents", 0L)
                .containsEntry("eta_ahead", true);

        // the kitchen display: a pickup, the customer arriving at that time, "Handed to customer" from ready
        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(get(k.base() + "/kitchen/live").with(cook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(orderId))
                .andExpect(jsonPath("$.items[0].fulfilmentMode").value("pickup"))
                .andExpect(jsonPath("$.items[0].handoff.party").value("customer"))
                .andExpect(jsonPath("$.items[0].handoff.state").value("arriving"))
                .andExpect(jsonPath("$.items[0].handoff.eta").isNotEmpty());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/ready", orderId).with(cook))
                .andExpect(status().isOk());
        mvc.perform(post(k.base() + "/kitchen/live/{o}/handoff", orderId).with(cook))
                .andExpect(status().isOk());
        var me = TestJwt.customer(customer);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> mvc.perform(
                                get("/api/v1/me/food-orders/{id}", orderId).with(me))
                        .andExpect(jsonPath("$.mode").value("pickup"))
                        .andExpect(jsonPath("$.stage").value("delivered")));
        assertThat(jdbc.sql("select state from orders.orders where id = ?")
                        .params(orderId)
                        .query(String.class)
                        .single())
                .isEqualTo("delivered");
    }

    /** S-89: the mode is never empty (V200 default) and only a pickup carries a customer arrival time. */
    @Test
    void everyOrderHasAModeAndOnlyPickupsACustomerArrival() {
        var id = Ids.next();
        jdbc.sql("insert into orders.orders (id, type, state) values (?, 'goods', 'placed')")
                .params(id)
                .update();
        assertThat(jdbc.sql("select fulfilment_mode from orders.orders where id = ?")
                        .params(id)
                        .query(String.class)
                        .single())
                .isEqualTo("delivery");
        assertThatThrownBy(() -> jdbc.sql("update orders.orders set customer_eta = now() where id = ?")
                        .params(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("update orders.orders set fulfilment_mode = null where id = ?")
                        .params(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** S-86/S-88: a food delivery goes to fulfilment with its coordinates; its tracking streams every kitchen step. */
    @Test
    void aFoodDeliveryIsDispatchedAndItsTrackingStreams() throws Exception {
        var orderId = startDelivery();
        confirm(orderId, TestJwt.customerWithMfa(customer)).andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                        select count(*) from fulfilment.deliveries
                                         where order_id = ? and order_type = 'food' and kind = 'direct'
                                           and (dropoff ->> 'lat')::float = ?""")
                                .params(orderId, NEAR_LAT)
                                .query(Long.class)
                                .single()
                        == 1);
        var me = TestJwt.customer(customer);
        var stream = mvc.perform(get("/api/v1/me/food-orders/{id}/events", orderId).with(me))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        var response = stream.getResponse();
        assertThat(response.getContentAsString()).contains("event:food").contains("\"stage\":\"paid\"");
        mvc.perform(get("/api/v1/me/food-orders/{id}/events", orderId).with(TestJwt.customer(data.user("Other"))))
                .andExpect(status().isNotFound());

        var cook = TestJwt.member(fx.member(k, MerchantRole.COOK));
        mvc.perform(post(k.base() + "/kitchen/live/{o}/accept", orderId).with(cook)).andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(response.getContentAsString()).contains("\"stage\":\"cooking\""));
        // accepted: fulfilment knows when it's ready and plans the courier's run, due at the kitchen then
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select ready_by is not null from fulfilment.deliveries where order_id = ?")
                        .params(orderId)
                        .query(Boolean.class)
                        .single());
        plan.plan(null);
        mvc.perform(get(k.base() + "/kitchen/live").with(cook))
                .andExpect(jsonPath("$.items[?(@.orderId == '%s')].handoff.party".formatted(orderId)).value("courier"))
                .andExpect(jsonPath("$.items[?(@.orderId == '%s')].handoff.state".formatted(orderId)).value("finding"));
        mvc.perform(get("/api/v1/me/food-orders/{id}", orderId).with(me))
                .andExpect(jsonPath("$.courier.state").value("planned"))
                .andExpect(jsonPath("$.courier.pin").isNotEmpty());
    }
}
