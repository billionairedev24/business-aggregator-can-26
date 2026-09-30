package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.application.SyncIntegrations.CommerceJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-35 through the api with the local fakes ({@code northline.commerce.provider=local}): connect → OAuth callback on the
 * api host → import of the fixture catalogue as drafts, existing SKUs linked, sealed tokens, webhook verification and
 * dedupe, hide on removal, scheduled reads, disconnect, and the authorization rules.
 */
class CommerceSyncApiTest extends CatalogueApiTest {

    static final String INTEGRATIONS = "/api/v1/merchants/{m}/listings/integrations";
    static final String FAKE_WEBHOOK_SECRET = "local-commerce-webhook-secret";

    @Autowired
    CommerceJobs jobs;

    /** "Connect": returns the consent URL (the fake's is the callback itself). */
    String startConnect(Business biz, String provider, String body) throws Exception {
        return json(mvc.perform(postJson(INTEGRATIONS + "/" + provider + "/connect", body, biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isOk()))
                .path("authorizationUrl")
                .asString();
    }

    /** Follows the consent URL back to the public callback (no token) and checks where the browser is sent. */
    void callback(String consentUrl, String expectedLocation) throws Exception {
        var uri = URI.create(consentUrl);
        mvc.perform(get(uri.getPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", expectedLocation))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    String connectShopify(Business biz) throws Exception {
        var consent = startConnect(biz, "shopify", "{\"shop\":\"prairie-parts\"}");
        assertThat(consent)
                .startsWith("http://localhost:8080/api/v1/commerce/oauth/shopify/callback?code=fake-")
                .contains("shop=prairie-parts.myshopify.com");
        callback(
                consent,
                "http://localhost:3100/b/" + biz.merchantId() + "/listings/bulk?commerce=shopify&result=connected");
        awaitImported(biz, "shopify");
        return integrationId(biz, "shopify");
    }

    void awaitImported(Business biz, String provider) {
        await(() -> assertThat(jdbc.sql(
                                "select sync_status from catalogue.integrations where merchant_id = ? and provider = ?")
                        .params(biz.merchantId(), provider)
                        .query(String.class)
                        .single())
                .isEqualTo("ok"));
    }

    String integrationId(Business biz, String provider) {
        return jdbc.sql("select id from catalogue.integrations where merchant_id = ? and provider = ?")
                .params(biz.merchantId(), provider)
                .query(String.class)
                .single();
    }

    String offerOf(Business biz, String provider, String externalId) {
        return jdbc.sql("""
                        select offer_id from catalogue.commerce_products
                        where merchant_id = ? and provider = ? and external_id = ?""")
                .params(biz.merchantId(), provider, externalId)
                .query(String.class)
                .single();
    }

    String offerColumn(String offerId, String column) {
        return String.valueOf(jdbc.sql("select " + column + " from catalogue.offers where id = ?")
                .params(offerId)
                .query((rs, _) -> rs.getObject(1))
                .single());
    }

    static String sign(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(FAKE_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void connectImportsTheCatalogueAsDrafts() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        mvc.perform(get(INTEGRATIONS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].provider").value("shopify"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].state").value("disconnected"));

        var id = connectShopify(biz);

        mvc.perform(get(INTEGRATIONS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(jsonPath("$.items[0].state").value("connected"))
                .andExpect(jsonPath("$.items[0].accountLabel").value("prairie-parts.myshopify.com"))
                .andExpect(jsonPath("$.items[0].syncStatus").value("ok"))
                .andExpect(jsonPath("$.items[0].createdCount").value(3))
                .andExpect(jsonPath("$.items[0].updates").value("hourly"))
                // "Clearance brake cleaner": a promo word the editor refuses, listed with the editor's message
                .andExpect(jsonPath("$.items[0].errors", hasSize(1)))
                .andExpect(jsonPath("$.items[0].errors[0].title").value("Clearance brake cleaner"))
                .andExpect(
                        jsonPath("$.items[0].errors[0].error").value("Leave out promo words like sale, free or best."));

        // the wiper blade family: one draft listing with three Length variants, both images, private until submitted
        var wiper = offerOf(biz, "shopify", "gid://shopify/Product/7001");
        assertThat(offerColumn(wiper, "vetting")).isEqualTo("draft");
        assertThat(offerColumn(wiper, "status")).isEqualTo("hidden");
        assertThat(offerColumn(wiper, "title")).isEqualTo("Bosch Icon wiper blade");
        assertThat(offerColumn(wiper, "variant_theme")).isEqualTo("length");
        assertThat(offerColumn(wiper, "cardinality(own_images)")).isEqualTo("2");
        assertThat(jdbc.sql("select sku from catalogue.variants where offer_id = ? order by position")
                        .params(wiper)
                        .query(String.class)
                        .list())
                .containsExactly("WB-ICON-22", "WB-ICON-24", "WB-ICON-26");
        assertThat(jdbc.sql("select count(*) from catalogue.commerce_variants where offer_id = ?")
                        .params(wiper)
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);

        // the tokens are sealed with the envelope key, bound to the integration id
        var stored = jdbc.sql("select token_ref, credentials_enc from catalogue.integrations where id = ?")
                .params(id)
                .query((rs, _) -> new String(rs.getBytes("credentials_enc"), StandardCharsets.ISO_8859_1))
                .single();
        assertThat(stored).doesNotContain("fake-access").doesNotContain("fake-refresh");

        // the editor opens it with what's still missing (category, compliance) — submission goes through vetting
        mvc.perform(get("/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), wiper)
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetting").value("draft"));
    }

    @Test
    void anExistingSkuIsLinkedAndUpdatedNotDuplicated() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var created = json(mvc.perform(postJson(
                                "/api/v1/merchants/{m}/products",
                                completeProduct("Motor oil 5W-30", "OIL-5W30-5L", 3999),
                                biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated()));
        var listingId = created.path("id").asString();

        connectShopify(biz);

        assertThat(offerOf(biz, "shopify", "gid://shopify/Product/7002")).isEqualTo(listingId);
        assertThat(offerColumn(listingId, "price_cents")).isEqualTo("4799");
        assertThat(Integer.parseInt(offerColumn(listingId, "stock"))).isBetween(20, 22);
        assertThat(offerColumn(listingId, "title")).isEqualTo("Motor oil 5W-30"); // content stays Northline's
        assertThat(jdbc.sql("select count(*) from catalogue.offers where merchant_id = ? and sku = 'OIL-5W30-5L'")
                        .params(biz.merchantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    /** S-39: price follows the platform, and a new price on an approved listing still sends it back to vetting. */
    @Test
    void aSyncedPriceChangeOnAnApprovedListingIsReVetted() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var listingId = json(mvc.perform(postJson(
                                        "/api/v1/merchants/{m}/products",
                                        completeProduct("Motor oil 5W-30", "OIL-5W30-5L", 3999),
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .path("id")
                .asString();
        jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                .params(listingId)
                .update();

        connectShopify(biz);

        assertThat(offerColumn(listingId, "price_cents")).isEqualTo("4799");
        assertThat(offerColumn(listingId, "title")).isEqualTo("Motor oil 5W-30");
        assertThat(captured.of(ca.northline.catalogue.api.ListingHidden.class, listingId))
                .hasSize(1);
        assertThat(captured.of(ca.northline.catalogue.api.ListingSubmitted.class, listingId))
                .singleElement()
                .satisfies(e -> assertThat(e.actorId()).isEqualTo("system:commerce"));
        // the automated checks then run as for any submission (approved or flagged, depending on the category median)
        await(() -> assertThat(offerColumn(listingId, "vetting_flags") + offerColumn(listingId, "vetting"))
                .matches(".*(approved|price_outlier.*pending).*"));
    }

    @Test
    void verifiedWebhookHidesARemovedProductOnceAndForgedOnesAreRefused() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        var integration = connectShopify(biz);
        var filter = offerOf(biz, "shopify", "gid://shopify/Product/7003");
        // the merchant had it live; a removal must hide it, never delete it
        jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                .params(filter)
                .update();

        var delivery = Ids.next();
        var body = """
                {"account":"prairie-parts.myshopify.com","deliveryId":"%s","type":"removed","id":"gid://shopify/Product/7003"}""".formatted(delivery);
        mvc.perform(post("/api/v1/webhooks/commerce/shopify")
                        .content(body)
                        .contentType(JSON)
                        .header("X-Fake-Signature", sign(body + " ")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_webhook"));
        mvc.perform(post("/api/v1/webhooks/commerce/shopify").content(body).contentType(JSON))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/webhooks/commerce/shopify")
                        .content(body)
                        .contentType(JSON)
                        .header("X-Fake-Signature", sign(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        await(() -> assertThat(offerColumn(filter, "status")).isEqualTo("hidden"));
        assertThat(offerColumn(filter, "vetting")).isEqualTo("approved");
        assertThat(jdbc.sql("select removed_at is not null from catalogue.commerce_products where offer_id = ?")
                        .params(filter)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(captured.of(ca.northline.catalogue.api.ListingHidden.class, filter))
                .hasSize(1);

        // the same delivery again (a platform retry): acknowledged, not applied twice
        mvc.perform(post("/api/v1/webhooks/commerce/shopify")
                        .content(body)
                        .contentType(JSON)
                        .header("X-Fake-Signature", sign(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // the hourly read finds the product again (the fixture still has it): link restored, listing stays hidden
        jdbc.sql("update catalogue.integrations set last_polled_at = now() - interval '2 hours' where id = ?")
                .params(integration)
                .update();
        assertThat(jobs.syncDue()).isPositive();
        assertThat(offerColumn(filter, "status")).isEqualTo("hidden");
        assertThat(jdbc.sql("select removed_at is null from catalogue.commerce_products where offer_id = ?")
                        .params(filter)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(offerOf(biz, "shopify", "gid://shopify/Product/7003")).isEqualTo(filter);
    }

    @Test
    void shopifyNeedsAValidStore() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        mvc.perform(postJson(INTEGRATIONS + "/shopify/connect", "{}", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("shop"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Enter your Shopify store address (your-store.myshopify.com)."));
        mvc.perform(postJson(INTEGRATIONS + "/shopify/connect", "{\"shop\":\"evil.example.com\"}", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].rule").value("format"));
        mvc.perform(post(INTEGRATIONS + "/etsy/connect", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void theStateIsSingleUseAndBoundToAManager() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        mvc.perform(get("/api/v1/commerce/oauth/square/callback")
                        .param("code", "fake-x")
                        .param("state", "made-up"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "http://localhost:3100/?commerce=square&result=expired"));

        var consent = startConnect(biz, "square", "{}");
        var denied = UriComponentsBuilder.fromUriString(consent)
                .replaceQueryParam("code")
                .queryParam("error", "access_denied")
                .build()
                .toUriString();
        callback(
                denied, "http://localhost:3100/b/" + biz.merchantId() + "/listings/bulk?commerce=square&result=denied");
        callback(consent, "http://localhost:3100/?commerce=square&result=expired"); // used up by the denial

        // an owner who is no longer allowed to manage the business when the browser comes back
        var again = startConnect(biz, "square", "{}");
        jdbc.sql("update merchants.merchant_members set role = 'technician' where merchant_id = ? and user_id = ?")
                .params(biz.merchantId(), biz.userId())
                .update();
        callback(again, "http://localhost:3100/b/" + biz.merchantId() + "/listings/bulk?commerce=square&result=failed");
        assertThat(jdbc.sql(
                                "select count(*) from catalogue.integrations where merchant_id = ? and status = 'connected'")
                        .params(biz.merchantId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void syncNowAndDisconnect() throws Exception {
        var biz = seller(MerchantRole.OWNER);
        mvc.perform(post(INTEGRATIONS + "/lightspeed/sync", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_connected"));
        var consent = startConnect(biz, "lightspeed", "{}");
        callback(
                consent,
                "http://localhost:3100/b/" + biz.merchantId() + "/listings/bulk?commerce=lightspeed&result=connected");
        awaitImported(biz, "lightspeed");

        var tech = member(biz.merchantId(), MerchantRole.TECHNICIAN);
        mvc.perform(post(INTEGRATIONS + "/lightspeed/sync", biz.merchantId()).with(TestJwt.member(tech)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("connected"))
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.lastSyncAt").isNotEmpty());

        var chains = offerOf(biz, "lightspeed", "0a6f6e36-8b0e-11ee-9f1a-000000000001");
        mvc.perform(post(INTEGRATIONS + "/lightspeed/disconnect", biz.merchantId())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("disconnected"))
                .andExpect(jsonPath("$.connected").value(false));
        assertThat(jdbc.sql(
                                "select credentials_enc is null from catalogue.integrations where merchant_id = ? and provider = 'lightspeed'")
                        .params(biz.merchantId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(offerColumn(chains, "title")).isEqualTo("Winter tire chains"); // imported listings stay
    }

    @Nested
    class Authorization {

        @Test
        void onlyTheOwnerConnectsAndDisconnects() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var tech = member(biz.merchantId(), MerchantRole.TECHNICIAN);
            mvc.perform(postJson(INTEGRATIONS + "/square/connect", "{}", biz.merchantId())
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(post(INTEGRATIONS + "/square/disconnect", biz.merchantId())
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void bookkeepersCantSyncAndOutsidersSeeNothing() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
            mvc.perform(post(INTEGRATIONS + "/square/sync", biz.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());
            var outsider = seller(MerchantRole.OWNER);
            mvc.perform(get(INTEGRATIONS, biz.merchantId()).with(TestJwt.member(outsider.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
        }

        @Test
        void aSecondFactorIsRequired() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(postJson(INTEGRATIONS + "/square/connect", "{}", biz.merchantId())
                            .with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }

        @Test
        void theCallbackAndWebhooksArePublicButTheListIsNot() throws Exception {
            mvc.perform(get(INTEGRATIONS, Ids.next())).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/commerce/oauth/shopify/callback"))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", startsWith("http://localhost:3100/")));
        }
    }
}
