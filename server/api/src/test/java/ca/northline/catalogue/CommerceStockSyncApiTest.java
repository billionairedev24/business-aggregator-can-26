package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.application.StockSync.PushStock;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.orders.api.SellableOffers;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-158 two-way stock through the api with the local fakes: a paid order line becomes a push to the connected store,
 * sent by the push job at the location the merchant chose (the fake Shopify has two, so the first push waits for the
 * choice); a restocked cancellation goes the other way; a redelivered event queues nothing new; retry and dismiss;
 * the authorization rules and messages.
 */
class CommerceStockSyncApiTest extends CatalogueApiTest {

    static final String INTEGRATIONS = "/api/v1/merchants/{m}/listings/integrations";

    @Autowired
    PushStock pushStock;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    SellableOffers offers;

    @Autowired
    PlatformTransactionManager transactions;

    String shop() {
        return "stock-" + Ids.next().toLowerCase(Locale.ROOT).substring(14);
    }

    /** Connects the fake Shopify for a store of its own (the fake keeps each store's stock apart). */
    void connect(Business biz, String provider, String shop) throws Exception {
        var url = json(mvc.perform(postJson(
                                        INTEGRATIONS + "/" + provider + "/connect",
                                        "{\"shop\":\"" + shop + "\"}",
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isOk()))
                .path("authorizationUrl")
                .asString();
        var uri = URI.create(url);
        mvc.perform(get(uri.getPath() + "?" + uri.getRawQuery())).andExpect(status().isSeeOther());
        await(() -> assertThat(jdbc.sql(
                                "select sync_status from catalogue.integrations where merchant_id = ? and provider = ?")
                        .params(biz.merchantId(), provider)
                        .query(String.class)
                        .single())
                .isEqualTo("ok"));
    }

    String offerOf(Business biz, String provider, String externalId) {
        return jdbc.sql("""
                        select offer_id from catalogue.commerce_products
                        where merchant_id = ? and provider = ? and external_id = ?""")
                .params(biz.merchantId(), provider, externalId)
                .query(String.class)
                .single();
    }

    void placed(Business biz, String orderId, OrderPlaced.Line... lines) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(_ -> events.publishEvent(new OrderPlaced(
                        Ids.next(),
                        Instant.now(),
                        orderId,
                        biz.merchantId(),
                        Ids.next(),
                        "NL-" + orderId.substring(20),
                        "goods",
                        "pooled",
                        null,
                        1000,
                        50,
                        List.of(lines))));
    }

    List<Map<String, Object>> pushes(Business biz) {
        return jdbc.sql("""
                        select id, state, conflict, delta, reason, sku, location_id, source_ref, platform_after
                        from catalogue.commerce_stock_pushes where merchant_id = ? order by created_at, id""").params(biz.merchantId()).query().listOfRows();
    }

    @Test
    void aSaleReachesTheStoreAtTheChosenLocation() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        connect(biz, "shopify", shop());
        var oil = offerOf(biz, "shopify", "gid://shopify/Product/7002");
        var orderId = Ids.next();
        placed(biz, orderId, new OrderPlaced.Line(Ids.next(), oil, null, 2, 4000));
        await(() -> assertThat(pushes(biz)).hasSize(1));
        assertThat(pushes(biz).getFirst())
                .containsEntry("state", "pending")
                .containsEntry("delta", -2)
                .containsEntry("reason", "sale")
                .containsEntry("sku", "OIL-5W30-5L")
                .containsEntry("source_ref", orderId);

        // the fake Shopify has two locations: the push waits for the merchant's choice
        pushStock.pushDue();
        assertThat(pushes(biz).getFirst())
                .containsEntry("state", "conflict")
                .containsEntry("conflict", "choose_location");
        mvc.perform(get(INTEGRATIONS + "/shopify/stock", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.canWrite").value(true))
                .andExpect(jsonPath("$.locationId").doesNotExist())
                .andExpect(jsonPath("$.attention", hasSize(1)))
                .andExpect(jsonPath("$.attention[0].conflict").value("choose_location"))
                .andExpect(jsonPath("$.attention[0].listingName").value("Synthetic motor oil 5W-30, 5 L"))
                .andExpect(jsonPath("$.attention[0].applied").value(false));
        mvc.perform(get(INTEGRATIONS + "/shopify/locations", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].name").value("Warehouse"))
                .andExpect(jsonPath("$.items[1].chosen").value(false));

        mvc.perform(putJson(
                                INTEGRATIONS + "/shopify/location",
                                "{\"locationId\":\"gid://shopify/Location/1002\"}",
                                biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value("gid://shopify/Location/1002"))
                .andExpect(jsonPath("$.locationName").value("Warehouse"))
                .andExpect(jsonPath("$.pending").value(1));
        assertThat(jdbc.sql("""
                        select count(*) from developer.audit_log
                        where merchant_id = ? and action = 'integration.location_chosen'""").params(biz.merchantId()).query(Integer.class).single())
                .isEqualTo(1);

        pushStock.pushDue();
        var done = pushes(biz).getFirst();
        assertThat(done).containsEntry("state", "done").containsEntry("location_id", "gid://shopify/Location/1002");
        assertThat((Integer) done.get("platform_after")).isLessThanOrEqualTo(20);

        // the connection lists the location too
        mvc.perform(get(INTEGRATIONS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items[0].locationName").value("Warehouse"));
    }

    @Test
    void aRedeliveredEventQueuesNothingNew_andVariantsAndRestocksGoToTheirVariant() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        connect(biz, "square", shop());
        var mats = offerOf(biz, "square", "SQ-ITEM-MATS");
        var grey = jdbc.sql("select id from catalogue.variants where offer_id = ? and sku = 'MAT-AW-GRY'")
                .params(mats)
                .query(String.class)
                .single();
        var orderId = Ids.next();
        var line = new OrderPlaced.Line(Ids.next(), mats, grey, 1, 5000);
        placed(biz, orderId, line);
        placed(biz, orderId, line);
        await(() -> assertThat(pushes(biz)).hasSize(1));
        Thread.sleep(300); // the second delivery's listener has run too
        assertThat(pushes(biz)).hasSize(1);

        // Square's fake has one location: used (and remembered) without asking
        pushStock.pushDue();
        assertThat(pushes(biz).getFirst())
                .containsEntry("state", "done")
                .containsEntry("sku", "MAT-AW-GRY")
                .containsEntry("location_id", "FAKE-SQUARE-LOCATION");
        assertThat(jdbc.sql(
                                "select location_name from catalogue.integrations where merchant_id = ? and provider = 'square'")
                        .params(biz.merchantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Main location");

        // a cancellation the shop restocked puts the unit back on the store too
        var cancellation = Ids.next();
        new TransactionTemplate(transactions)
                .executeWithoutResult(_ -> offers.restock(
                        List.of(new SellableOffers.Take(mats, grey, 1), new SellableOffers.Take(mats, grey, 1)),
                        "cancellation",
                        cancellation));
        var restock = pushes(biz).getLast();
        assertThat(restock)
                .containsEntry("state", "pending")
                .containsEntry("delta", 2)
                .containsEntry("reason", "cancellation")
                .containsEntry("source_ref", cancellation);
        pushStock.pushDue();
        assertThat(pushes(biz).getLast()).containsEntry("state", "done");

        // a food line or an offer no store is linked to queues nothing
        placed(biz, Ids.next(), new OrderPlaced.Line(Ids.next(), Ids.next(), null, 1, 900, "menu_item"));
        placed(biz, Ids.next(), new OrderPlaced.Line(Ids.next(), Ids.next(), null, 1, 900));
        Thread.sleep(300);
        assertThat(pushes(biz)).hasSize(2);
    }

    @Test
    void retryAndDismiss() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        connect(biz, "square", shop());
        var integration = jdbc.sql(
                        "select id from catalogue.integrations where merchant_id = ? and provider = 'square'")
                .params(biz.merchantId())
                .query(String.class)
                .single();
        var coolant = offerOf(biz, "square", "SQ-ITEM-COOLANT");
        var refused = conflictRow(biz, integration, coolant, "not_found", false);
        var oversold = conflictRow(biz, integration, coolant, "oversold", true);

        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/retry", biz.merchantId(), refused)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.attempts").value(0));
        // applied on the platform already: retrying would apply it twice
        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/retry", biz.merchantId(), oversold)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("push_applied"))
                .andExpect(jsonPath("$.detail")
                        .value("This change already reached the platform; it can only be dismissed."));
        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/dismiss", biz.merchantId(), oversold)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("dismissed"));
        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/dismiss", biz.merchantId(), oversold)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("push_closed"));
        // another business's change is not found
        var other = seller(MerchantRole.OWNER);
        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/retry", other.merchantId(), refused)
                        .with(TestJwt.member(other.userId())))
                .andExpect(status().isNotFound());

        // a disconnected store: the waiting change becomes a conflict instead of being sent
        mvc.perform(post(INTEGRATIONS + "/square/disconnect", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
        pushStock.pushDue();
        assertThat(jdbc.sql("select conflict from catalogue.commerce_stock_pushes where id = ?")
                        .params(refused)
                        .query(String.class)
                        .single())
                .isEqualTo("disconnected");
    }

    String conflictRow(Business biz, String integration, String offerId, String conflict, boolean applied) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into catalogue.commerce_stock_pushes (id, merchant_id, provider, integration_id, offer_id,
                          sku, external_variant_id, delta, reason, source_ref, idempotency_key, state, conflict,
                          attempts, next_attempt_at, created_at, done_at)
                        values (?, ?, 'square', ?, ?, 'COOL-5050', 'SQ-VAR-COOLANT', -1, 'sale', ?, ?, 'conflict', ?, 1,
                          now(), now(), ?)""")
                .params(
                        id,
                        biz.merchantId(),
                        integration,
                        offerId,
                        Ids.next(),
                        "test:" + id,
                        conflict,
                        applied ? java.sql.Timestamp.from(Instant.now()) : null)
                .update();
        return id;
    }

    @Test
    void rolesAndMessages() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        connect(biz, "shopify", shop());
        var technician = member(biz.merchantId(), MerchantRole.TECHNICIAN);
        var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
        var outsider = seller(MerchantRole.OWNER);
        var location = "{\"locationId\":\"gid://shopify/Location/1001\"}";

        // reading: the whole team; choosing the location: the owner; retry / dismiss: who edits listings
        mvc.perform(get(INTEGRATIONS + "/shopify/stock", biz.merchantId()).with(TestJwt.member(bookkeeper)))
                .andExpect(status().isOk());
        mvc.perform(putJson(INTEGRATIONS + "/shopify/location", location, biz.merchantId())
                        .with(TestJwt.member(technician)))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTEGRATIONS + "/shopify/locations", biz.merchantId()).with(TestJwt.member(technician)))
                .andExpect(status().isForbidden());
        mvc.perform(post(INTEGRATIONS + "/stock-changes/{p}/retry", biz.merchantId(), Ids.next())
                        .with(TestJwt.member(bookkeeper)))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTEGRATIONS + "/shopify/stock", biz.merchantId()).with(TestJwt.member(outsider.userId())))
                .andExpect(status().isForbidden());
        mvc.perform(putJson(INTEGRATIONS + "/shopify/location", location, biz.merchantId())
                        .with(TestJwt.memberWithoutMfa(biz.userId())))
                .andExpect(status().isForbidden());

        // 422: a location the store doesn't have, or none
        mvc.perform(putJson(
                                INTEGRATIONS + "/shopify/location",
                                "{\"locationId\":\"gid://shopify/Location/9\"}",
                                biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("locationId"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of the store's locations."));
        mvc.perform(putJson(INTEGRATIONS + "/shopify/location", "{}", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose one of the store's locations."));
        // not connected: nothing to read
        mvc.perform(get(INTEGRATIONS + "/lightspeed/locations", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(get(INTEGRATIONS + "/lightspeed/stock", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }
}
