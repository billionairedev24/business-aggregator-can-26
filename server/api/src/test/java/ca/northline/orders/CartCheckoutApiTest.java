package ca.northline.orders;

import static ca.northline.support.ShopFixtures.BAKERY;
import static ca.northline.support.ShopFixtures.BUTCHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.orders.api.OrderPlaced;
import ca.northline.orders.application.CheckoutUseCases.ExpireCheckouts;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-51: the server-side cart (guests by X-Northline-Guest, people by token, merged at sign-in) and checkout — step-up
 * rule, Idempotency-Key, validation messages, tax, one PaymentIntent per line (fake gateway), escrow holds, the order
 * and {@code order.placed} per shop, stock races and expiry. Market "Cartville" (application-test.yml).
 */
@RecordApplicationEvents
class CartCheckoutApiTest extends IntegrationTest {

    static final String MARKET = "Cartville";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    ExpireCheckouts expiry;

    String bakery;
    String butcher;
    Listing bread;
    Listing steak;

    @BeforeEach
    void shops() {
        shopFixtures.categories();
        bakery = shopFixtures.shop(MARKET, "Glenmore Bakery", "master");
        butcher = shopFixtures.shop(MARKET, "Bridgeland Butcher", "trusted");
        bread = shopFixtures.listing(bakery, BAKERY, "Country sourdough", 750, 10);
        steak = shopFixtures.listing(butcher, BUTCHER, "Ribeye", 1850, 3);
    }

    static String guest() {
        return "g_" + Ids.next().toLowerCase(Locale.ROOT);
    }

    static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    ResultActions add(MockHttpServletRequestBuilder request, String offerId, int qty) throws Exception {
        return mvc.perform(body(request, "{\"offerId\":\"%s\",\"qty\":%d}".formatted(offerId, qty)));
    }

    // ── cart ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    class Cart {

        @Test
        void aGuestHasACartKeyedByTheirBrowsingSession() throws Exception {
            var g = guest();
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), bread.offerId(), 2)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.itemCount").value(2));
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), steak.offerId(), 1)
                    .andExpect(jsonPath("$.itemCount").value(3))
                    .andExpect(jsonPath("$.shopCount").value(2))
                    .andExpect(jsonPath("$.subtotalCents").value(2 * 750 + 1850))
                    .andExpect(jsonPath("$.groups[0].shopName").value("Glenmore Bakery"))
                    .andExpect(jsonPath("$.groups[1].items[0].name").value("Ribeye"));
            mvc.perform(get("/api/v1/cart").header("X-Northline-Guest", g))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.itemCount").value(3));
            mvc.perform(get("/api/v1/cart").header("X-Northline-Guest", guest()))
                    .andExpect(jsonPath("$.itemCount").value(0));
            mvc.perform(get("/api/v1/cart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemCount").value(0));
            // the raw guest id is never stored
            assertThat(jdbc.sql("select count(*) from orders.carts where device_key = ?")
                            .params(g)
                            .query(Long.class)
                            .single())
                    .isZero();
        }

        @Test
        void changesAreValidatedWithTheirMessages() throws Exception {
            var g = guest();
            add(post("/api/v1/cart/items"), bread.offerId(), 1)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("X-Northline-Guest"))
                    .andExpect(
                            jsonPath("$.errors[0].message").value("Your browsing session expired. Reload the page."));
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), bread.offerId(), 0)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a quantity from 1 to 99."));
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), steak.offerId(), 4)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Only 3 left."));
            var draft = shopFixtures.listing(bakery, BAKERY, "Draft loaf", 500, 5, "draft", "hidden", "same_day", 0);
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), draft.offerId(), 1)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("This item isn't available any more."));
            var loaf = shopFixtures.listing(bakery, BAKERY, "Sliced loaf", 0, 0);
            shopFixtures.variant(loaf.offerId(), "Whole", 700, 2, 0);
            var sliced = shopFixtures.variant(loaf.offerId(), "Sliced", 750, 2, 1);
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), loaf.offerId(), 1)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("variantId"))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose an option."));
            mvc.perform(body(
                            post("/api/v1/cart/items").header("X-Northline-Guest", g),
                            "{\"offerId\":\"%s\",\"variantId\":\"%s\",\"qty\":2}".formatted(loaf.offerId(), sliced)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.groups[0].items[0].option").value("Sliced"))
                    .andExpect(jsonPath("$.groups[0].items[0].unitCents").value(750));
        }

        @Test
        void quantitiesChangeAndLinesGo() throws Exception {
            var g = guest();
            var cart = json(add(post("/api/v1/cart/items").header("X-Northline-Guest", g), bread.offerId(), 1));
            var itemId = cart.path("groups")
                    .get(0)
                    .path("items")
                    .get(0)
                    .path("itemId")
                    .asString();
            mvc.perform(body(patch("/api/v1/cart/items/{id}", itemId).header("X-Northline-Guest", g), "{\"qty\":4}"))
                    .andExpect(jsonPath("$.itemCount").value(4));
            mvc.perform(body(patch("/api/v1/cart/items/{id}", itemId).header("X-Northline-Guest", g), "{\"qty\":11}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Only 10 left."));
            // another browsing session can't touch it
            mvc.perform(delete("/api/v1/cart/items/{id}", itemId).header("X-Northline-Guest", guest()))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/cart/items/{id}", itemId).header("X-Northline-Guest", g))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemCount").value(0));
        }

        @Test
        void signingInMergesTheGuestsCartIntoThePersons() throws Exception {
            var g = guest();
            var amara = data.user("Amara Osei");
            add(post("/api/v1/cart/items").with(TestJwt.customer(amara)), bread.offerId(), 1)
                    .andExpect(status().isCreated());
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), bread.offerId(), 2);
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), steak.offerId(), 1);
            mvc.perform(get("/api/v1/cart").with(TestJwt.customer(amara)).header("X-Northline-Guest", g))
                    .andExpect(jsonPath("$.itemCount").value(4))
                    .andExpect(jsonPath("$.groups[0].items[0].qty").value(3));
            mvc.perform(get("/api/v1/cart").header("X-Northline-Guest", g))
                    .andExpect(jsonPath("$.itemCount").value(0));
        }

        @Test
        void aHiddenListingOrPausedShopMakesTheLineUnavailable() throws Exception {
            var g = guest();
            add(post("/api/v1/cart/items").header("X-Northline-Guest", g), bread.offerId(), 1);
            jdbc.sql("update merchants.merchants set status = 'paused' where id = ?")
                    .params(bakery)
                    .update();
            mvc.perform(get("/api/v1/cart").header("X-Northline-Guest", g))
                    .andExpect(jsonPath("$.groups[0].items[0].available").value(false))
                    .andExpect(jsonPath("$.subtotalCents").value(0));
        }
    }

    // ── checkout ──────────────────────────────────────────────────────────────────────────────────────────────────

    static final String ADDRESS = """
            {"street":"1204 17 Ave SW","unit":"Apt 804","city":"%s","province":"AB","postal":"t2t0b8","note":"Buzz 0804"}""".formatted(MARKET);

    String checkoutBody(String windowId) {
        return """
                {"kind":"pooled","windowId":"%s","address":%s,"substitution":"similar"}""".formatted(windowId, ADDRESS);
    }

    String firstWindow(String user) throws Exception {
        var setup = json(
                mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customerWithMfa(user)))
                        .andExpect(status().isOk()));
        return setup.path("options").get(0).path("windowId").asString();
    }

    ResultActions start(RequestPostProcessor auth, String json, String key) throws Exception {
        return start(auth, null, json, key);
    }

    ResultActions start(RequestPostProcessor auth, @Nullable String proof, String json, String key) throws Exception {
        var request = body(post("/api/v1/me/checkouts"), json).with(auth).header("Idempotency-Key", key);
        if (proof != null) {
            request.header("X-Step-Up", proof);
        }
        return mvc.perform(request);
    }

    @Nested
    class Checkout {

        String amara;

        @BeforeEach
        void customer() throws Exception {
            amara = data.user("Amara Osei");
            add(post("/api/v1/cart/items").with(TestJwt.customer(amara)), bread.offerId(), 2)
                    .andExpect(status().isCreated());
            add(post("/api/v1/cart/items").with(TestJwt.customer(amara)), steak.offerId(), 1)
                    .andExpect(status().isCreated());
        }

        @Test
        void setupListsRunsTheDirectCourierAndTheStepUpNeed() throws Exception {
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customer(amara)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stepUp").value("enrol"))
                    .andExpect(jsonPath("$.payment.provider").value("fake"))
                    .andExpect(jsonPath("$.cart.itemCount").value(3))
                    .andExpect(jsonPath("$.options[0].kind").value("pooled"))
                    .andExpect(jsonPath("$.options[0].feeCents").isNumber())
                    .andExpect(jsonPath("$.options[2].kind").value("direct"))
                    .andExpect(jsonPath("$.options[2].etaMinutes").value(45))
                    .andExpect(jsonPath("$.options[2].feeCents").value(999));
            jdbc.sql("update identity.users set mfa_primary = 'totp' where id = ?")
                    .params(amara)
                    .update();
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.stepUp").value("required"));
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customerWithMfa(amara)))
                    .andExpect(jsonPath("$.stepUp").value("none"));
        }

        @Test
        void quoteAddsGstOnItemsAndDelivery() throws Exception {
            var window = firstWindow(amara);
            var fee = json(mvc.perform(
                            get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customer(amara))))
                    .path("options")
                    .get(0)
                    .path("feeCents")
                    .asLong();
            var subtotal = 2 * 750 + 1850;
            var quote = json(mvc.perform(body(post("/api/v1/me/checkout/quote"), checkoutBody(window))
                            .with(TestJwt.customer(amara)))
                    .andExpect(status().isOk()));
            assertThat(quote.path("subtotalCents").asLong()).isEqualTo(subtotal);
            assertThat(quote.path("deliveryFeeCents").asLong()).isEqualTo(fee);
            assertThat(quote.path("taxes").get(0).path("type").asString()).isEqualTo("gst");
            assertThat(quote.path("taxes").get(0).path("percent").asDouble()).isEqualTo(5.0);
            // 5 % per line and on the delivery fee, each rounded
            var expectedTax = Math.round(1500 * 0.05) + Math.round(1850 * 0.05) + Math.round(fee * 0.05);
            assertThat(quote.path("taxCents").asLong()).isEqualTo(expectedTax);
            assertThat(quote.path("totalCents").asLong()).isEqualTo(subtotal + fee + expectedTax);
        }

        @Test
        void addressAndChoicesAreValidated() throws Exception {
            var window = firstWindow(amara);
            mvc.perform(body(post("/api/v1/me/checkout/quote"), """
                            {"kind":"pooled","windowId":"%s","address":{"street":" ","city":"","province":"XX","postal":"123"},"substitution":"similar"}""".formatted(window))
                            .with(TestJwt.customer(amara)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field=='address.street')].message")
                            .value("Enter the street address."))
                    .andExpect(jsonPath("$.errors[?(@.field=='address.city')].message")
                            .value("Enter the city."))
                    .andExpect(jsonPath("$.errors[?(@.field=='address.province')].message")
                            .value("Choose a Canadian province or territory."))
                    .andExpect(jsonPath("$.errors[?(@.field=='address.postal')].message")
                            .value("Enter a Canadian postal code, like T2P 1B5."));
            mvc.perform(body(
                                    post("/api/v1/me/checkout/quote"),
                                    checkoutBody(window).replace("similar", "maybe"))
                            .with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose what we do if something's out of stock."));
            mvc.perform(body(
                                    post("/api/v1/me/checkout/quote"),
                                    checkoutBody(window).replace(MARKET, "Red Deer"))
                            .with(TestJwt.customer(amara)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("address.city"))
                    .andExpect(jsonPath("$.errors[0].message").value("We don't deliver to Red Deer yet."));
            mvc.perform(body(post("/api/v1/me/checkout/quote"), checkoutBody(Ids.next()))
                            .with(TestJwt.customer(amara)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("window_closed"));
            mvc.perform(body(
                                    post("/api/v1/me/checkout/quote"),
                                    checkoutBody(window).replace("\"pooled\"", "\"\""))
                            .with(TestJwt.customer(amara)))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a delivery window."));
        }

        @Test
        void aPhoneCodeSignInStepsUpFirst() throws Exception {
            var window = firstWindow(amara);
            start(TestJwt.customer(amara), checkoutBody(window), Ids.next())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("second_factor_required"));
            jdbc.sql("update identity.users set mfa_primary = 'passkey' where id = ?")
                    .params(amara)
                    .update();
            start(TestJwt.customer(amara), checkoutBody(window), Ids.next())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            start(TestJwt.customer(amara), "not-a-proof", checkoutBody(window), Ids.next())
                    .andExpect(jsonPath("$.code").value("step_up_required"));
            // the test profile's dev proof (like a fresh northline-auth step-up)
            start(TestJwt.customer(amara), "dev", checkoutBody(window), Ids.next())
                    .andExpect(status().isCreated());
        }

        @Test
        void moneyMovingPostsNeedAnIdempotencyKeyAndReplayTheirAnswer() throws Exception {
            var window = firstWindow(amara);
            mvc.perform(body(post("/api/v1/me/checkouts"), checkoutBody(window)).with(TestJwt.customerWithMfa(amara)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                    .andExpect(jsonPath("$.errors[0].message").value("Idempotency-Key header is required."));
            var key = Ids.next();
            var first = start(TestJwt.customerWithMfa(amara), checkoutBody(window), key)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            start(TestJwt.customerWithMfa(amara), checkoutBody(window), key)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(r ->
                            assertThat(r.getResponse().getContentAsString()).isEqualTo(first));
            start(TestJwt.customerWithMfa(amara), checkoutBody(window).replace("similar", "refund"), key)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
            // one checkout, one stock hold
            assertThat(stock(bread)).isEqualTo(8);
        }

        @Test
        void placingTheOrderHoldsEscrowPublishesOrderPlacedPerShopAndEmptiesTheCart() throws Exception {
            var window = firstWindow(amara);
            var started = json(start(TestJwt.customerWithMfa(amara), checkoutBody(window), Ids.next())
                    .andExpect(status().isCreated()));
            assertThat(started.path("ref").asString()).startsWith("NL-");
            assertThat(started.path("payment").path("provider").asString()).isEqualTo("fake");
            // one PaymentIntent per order line + one for the delivery fee, all authorized by the fake
            var intents = started.path("intents");
            assertThat(intents.size()).isEqualTo(3);
            intents.forEach(i -> assertThat(i.path("status").asString()).isEqualTo("authorized"));
            assertThat(stock(bread)).isEqualTo(8);
            assertThat(stock(steak)).isEqualTo(2);

            var checkoutId = started.path("checkoutId").asString();
            var placed = json(mvc.perform(post("/api/v1/me/checkouts/{id}/place", checkoutId)
                            .with(TestJwt.customerWithMfa(amara))
                            .header("Idempotency-Key", Ids.next()))
                    .andExpect(status().isCreated()));
            var orderId = placed.path("orderId").asString();
            assertThat(orderId).isEqualTo(started.path("orderId").asString());

            var order = jdbc.sql("""
                                    select state, type, window_id, delivery_kind, customer_id, tax_cents, fulfilment_mode,
                                           customer_eta is null as no_customer_eta
                                      from orders.orders where id = ?""").params(orderId).query().singleRow();
            assertThat(order)
                    .containsEntry("state", "placed")
                    .containsEntry("type", "goods")
                    .containsEntry("window_id", window)
                    .containsEntry("delivery_kind", "pooled")
                    // S-89: a shop order is delivered; customer_eta is a pickup customer's arrival, so none
                    .containsEntry("fulfilment_mode", "delivery")
                    .containsEntry("no_customer_eta", true)
                    .containsEntry("customer_id", amara);
            assertThat(jdbc.sql("select count(*) from orders.order_lines where order_id = ?")
                            .params(orderId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(2);
            assertThat(jdbc.sql("""
                            select count(*) from payments.escrows e join orders.order_lines l on l.id = e.ref_id
                             where l.order_id = ? and e.ref_type = 'order_line'""").params(orderId).query(Long.class).single()).isEqualTo(2);

            List<OrderPlaced> placedEvents = events.stream(OrderPlaced.class)
                    .filter(e -> e.aggregateId().equals(orderId))
                    .toList();
            assertThat(placedEvents).extracting(OrderPlaced::merchantId).containsExactlyInAnyOrder(bakery, butcher);
            var bakeryEvent = placedEvents.stream()
                    .filter(e -> e.merchantId().equals(bakery))
                    .findFirst()
                    .orElseThrow();
            assertThat(bakeryEvent.subtotalCents()).isEqualTo(1500);
            assertThat(bakeryEvent.lines()).singleElement().satisfies(l -> {
                assertThat(l.offerId()).isEqualTo(bread.offerId());
                assertThat(l.qty()).isEqualTo(2);
            });

            mvc.perform(get("/api/v1/cart").with(TestJwt.customerWithMfa(amara)))
                    .andExpect(jsonPath("$.itemCount").value(0));
            // placing again answers the same order
            mvc.perform(post("/api/v1/me/checkouts/{id}/place", checkoutId)
                            .with(TestJwt.customerWithMfa(amara))
                            .header("Idempotency-Key", Ids.next()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId));
            // someone else's checkout is invisible
            mvc.perform(post("/api/v1/me/checkouts/{id}/place", checkoutId)
                            .with(TestJwt.customerWithMfa(data.user("Other")))
                            .header("Idempotency-Key", Ids.next()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anUnauthorizedPaymentDoesNotPlaceTheOrder() throws Exception {
            var window = firstWindow(amara);
            var started = json(start(TestJwt.customerWithMfa(amara), checkoutBody(window), Ids.next()));
            var checkoutId = started.path("checkoutId").asString();
            jdbc.sql("""
                            update orders.checkouts set lines = jsonb_set(lines, '{0,paymentIntent}', '"pi_requires_action_test"')
                             where id = ?""").params(checkoutId).update();
            mvc.perform(post("/api/v1/me/checkouts/{id}/place", checkoutId)
                            .with(TestJwt.customerWithMfa(amara))
                            .header("Idempotency-Key", Ids.next()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("payment_not_authorized"));
            assertThat(jdbc.sql("select count(*) from orders.orders where checkout_id = ?")
                            .params(checkoutId)
                            .query(Long.class)
                            .single())
                    .isZero();
        }

        @Test
        void anAbandonedCheckoutGivesTheStockBackAndCantBePlaced() throws Exception {
            var window = firstWindow(amara);
            var started = json(start(TestJwt.customerWithMfa(amara), checkoutBody(window), Ids.next()));
            assertThat(stock(steak)).isEqualTo(2);
            expiry.expire(Instant.now().plus(Duration.ofMinutes(31)));
            assertThat(stock(steak)).isEqualTo(3);
            mvc.perform(post(
                                    "/api/v1/me/checkouts/{id}/place",
                                    started.path("checkoutId").asString())
                            .with(TestJwt.customerWithMfa(amara))
                            .header("Idempotency-Key", Ids.next()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("checkout_expired"));
        }

        @Test
        void anEmptyCartCantCheckOut() throws Exception {
            var window = firstWindow(amara);
            var nobody = data.user("Empty Cart");
            start(TestJwt.customerWithMfa(nobody), checkoutBody(window), Ids.next())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("cart_empty"));
        }
    }

    @Test
    void theLastUnitGoesToOneCheckoutOnly() throws Exception {
        var last = shopFixtures.listing(bakery, BAKERY, "Last cake", 3000, 1);
        var buyers = List.of(data.user("Buyer One"), data.user("Buyer Two"));
        for (var buyer : buyers) {
            add(post("/api/v1/cart/items").with(TestJwt.customer(buyer)), last.offerId(), 1)
                    .andExpect(status().isCreated());
        }
        var window = firstWindow(buyers.getFirst());
        var ready = new CountDownLatch(1);
        var statuses = new ArrayList<Integer>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = buyers.stream()
                    .map(buyer -> pool.submit(() -> {
                        ready.await();
                        return start(TestJwt.customerWithMfa(buyer), checkoutBody(window), Ids.next())
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();
            ready.countDown();
            for (var f : futures) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(stock(last)).isZero();
    }

    int stock(Listing listing) {
        return jdbc.sql("select stock from catalogue.offers where id = ?")
                .params(listing.offerId())
                .query(Integer.class)
                .single();
    }
}
