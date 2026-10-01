package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.orders.api.SellableOffers;
import ca.northline.orders.api.SellableOffers.Item;
import ca.northline.orders.api.SellableOffers.Take;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-65: bundles (own products only, stock that follows the items, sold through the cart all-or-nothing), per-variant
 * images (own media only, shown once approved) and the Compliance tab's documents.
 */
class BundlesAndDocumentsApiTest extends CatalogueApiTest {

    static final String PRODUCTS = "/api/v1/merchants/{m}/products";
    static final String LISTING = "/api/v1/merchants/{m}/listings/{id}";
    static final String DOCUMENTS = "/api/v1/merchants/{m}/listings/{id}/documents";

    @Autowired
    SellableOffers sellable;

    @Autowired
    TransactionTemplate tx;

    String product(Business biz, String sku, int stock) throws Exception {
        return json(mvc.perform(postJson(
                                        PRODUCTS,
                                        completeProduct("Wiper " + sku, sku, 1900)
                                                .replace("\"stock\":10", "\"stock\":" + stock),
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    void approve(String offerId) {
        jdbc.sql("update catalogue.offers set vetting = 'approved', status = 'live' where id = ?")
                .params(offerId)
                .update();
    }

    int stock(String offerId) {
        return jdbc.sql("select stock from catalogue.offers where id = ?")
                .params(offerId)
                .query(Integer.class)
                .single();
    }

    static String bundle(String title, String items, long priceCents) {
        return """
                {"type":"bundle","identifierType":"gtin","gtin":"028851200226","title":"%s","categoryId":"%s",
                 "attributes":{"partType":"Wiper blades","length":"22 in","position":"Front"},"variantTheme":"length",
                 "variants":[{"value":"x","sku":"X-1","priceCents":100,"stock":1}],
                 "priceCents":%d,"stock":50,"fulfilment":["pooled"],"imageSource":"own","countryOfOrigin":"CA",
                 "restrictedOk":true,"bilingualOk":true,"imageIds":[],"bundleItems":%s}
                """.formatted(title, AUTO_PARTS, priceCents, items);
    }

    @Nested
    class Bundles {

        @Test
        void aBundleOfOwnProducts_hasNoIdentifierOrVariants_andItsStockFollowsTheItems() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var pads = product(biz, "BP-" + biz.merchantId().substring(20), 9);
            var fluid = product(biz, "WF-" + biz.merchantId().substring(20), 3);
            var body = bundle("Brake kit", """
                    [{"offerId":"%s","qty":2},{"offerId":"%s","qty":1}]""".formatted(pads, fluid), 3000);
            var created = json(mvc.perform(
                            postJson(PRODUCTS, body, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("bundle"))
                    .andExpect(jsonPath("$.identifierType").value("none"))
                    .andExpect(jsonPath("$.gtin").doesNotExist())
                    .andExpect(jsonPath("$.variants").isEmpty())
                    .andExpect(jsonPath("$.stock").value(3)) // min(9 / 2, 3 / 1)
                    .andExpect(jsonPath("$.bundleItems[0].offerId").value(pads))
                    .andExpect(jsonPath("$.bundleItems[0].qty").value(2))
                    .andExpect(jsonPath("$.bundleItems[0].name")
                            .value("Wiper BP-" + biz.merchantId().substring(20)))
                    .andExpect(jsonPath("$.bundleItems[0].unitPriceCents").value(1900))
                    .andExpect(jsonPath("$.bundleItems[1].stock").value(3)));
            var id = created.get("id").asString();
            mvc.perform(get("/api/v1/merchants/{m}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].bundle".formatted(id))
                            .value(true))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].stock".formatted(id))
                            .value(3))
                    .andExpect(jsonPath("$.items[?(@.id == '%s')].meta".formatted(id))
                            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Bundle · "))));

            // a component can't be deleted while a bundle holds it
            mvc.perform(delete(LISTING, biz.merchantId(), pads).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("listing_in_bundle"));
        }

        @Test
        void itemsMustBeOwnProducts_notBundles_withAVariantWhenTheyHaveSome() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var other = seller(MerchantRole.OWNER);
            var own = product(biz, "OWN-" + biz.merchantId().substring(20), 5);
            var theirs = product(other, "THR-" + other.merchantId().substring(20), 5);
            var kit = json(mvc.perform(postJson(
                                            PRODUCTS,
                                            bundle("Kit", "[{\"offerId\":\"%s\",\"qty\":2}]".formatted(own), 3000),
                                            biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            var sized = json(mvc.perform(postJson(
                                            PRODUCTS,
                                            completeProduct(
                                                            "Sized",
                                                            "SZ-"
                                                                    + biz.merchantId()
                                                                            .substring(20),
                                                            1900)
                                                    .replace(
                                                            "\"stock\":10,",
                                                            "\"stock\":10,\"variantTheme\":\"length\",\"variants\":[{\"value\":\"20 in\","
                                                                    + "\"sku\":\"SZ20-"
                                                                    + biz.merchantId()
                                                                            .substring(20)
                                                                    + "\",\"priceCents\":1700,\"stock\":2}],"),
                                            biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            mvc.perform(postJson(
                                    PRODUCTS,
                                    bundle("Bad", """
                                            [{"offerId":"%s","qty":1},{"offerId":"%s","qty":1},{"offerId":"%s","qty":1},
                                             {"offerId":"%s","qty":1},{"offerId":"%s","qty":1}]""".formatted(theirs, kit, sized, own, own), 3000),
                                    biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'bundleItems[0].offerId')].message")
                            .value("Choose one of your own products."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'bundleItems[1].offerId')].message")
                            .value("A bundle can't contain another bundle."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'bundleItems[2].variantId')].message")
                            .value("Choose a variant."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'bundleItems[4].offerId')].message")
                            .value("This item is already in the bundle — change its quantity."));
            mvc.perform(postJson(
                                    PRODUCTS,
                                    bundle("Bad", "[{\"offerId\":\"%s\",\"qty\":100}]".formatted(own), 3000),
                                    biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("bundleItems[0].qty"))
                    .andExpect(jsonPath("$.errors[0].message").value("Quantity must be between 1 and 99."));
        }

        @Test
        void submittingNeedsTwoUnitsAndApprovedItems() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var pads = product(biz, "SP-" + biz.merchantId().substring(20), 4);
            var kit = json(mvc.perform(postJson(
                                            PRODUCTS,
                                            bundle("Kit", "[{\"offerId\":\"%s\",\"qty\":1}]".formatted(pads), 3000),
                                            biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.completeness.missing[?(@.field == 'bundleItems')].message")
                                    .value("Add at least two items to the bundle.")))
                    .get("id")
                    .asString();
            mvc.perform(post(LISTING + "/submit", biz.merchantId(), kit).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent());
            var image = json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                                    .file(new MockMultipartFile(
                                            "file", "k.png", "image/png", png(1200, 1200, Color.WHITE)))
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            mvc.perform(putJson(
                                    PRODUCTS + "/{id}",
                                    bundle("Kit", "[{\"offerId\":\"%s\",\"qty\":2}]".formatted(pads), 3000)
                                            .replace("\"imageIds\":[]", "\"imageIds\":[\"" + image + "\"]"),
                                    biz.merchantId(),
                                    kit)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk());
            mvc.perform(post(LISTING + "/submit", biz.merchantId(), kit).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Every item in a bundle must be an approved listing."));
            approve(pads);
            mvc.perform(post(LISTING + "/submit", biz.merchantId(), kit).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk());
        }

        @Test
        void theCartSellsWholeBundles_andTakesEveryItemOrNone() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var pads = product(biz, "CP-" + biz.merchantId().substring(20), 5);
            var fluid = product(biz, "CF-" + biz.merchantId().substring(20), 1);
            var kit = json(mvc.perform(postJson(
                                            PRODUCTS,
                                            bundle(
                                                    "Kit",
                                                    "[{\"offerId\":\"%s\",\"qty\":2},{\"offerId\":\"%s\",\"qty\":1}]"
                                                            .formatted(pads, fluid),
                                                    3000),
                                            biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
            List.of(pads, fluid, kit).forEach(id -> approve(id));

            var found = sellable.find(List.of(new Item(kit, null)), "en");
            assertThat(found).singleElement().satisfies(s -> {
                assertThat(s.stock()).isEqualTo(1);
                assertThat(s.unitCents()).isEqualTo(3000);
            });
            // two bundles need 4 pads and 2 fluids: only 1 fluid — nothing is taken
            List<Take> refused = tx.execute(_ -> sellable.take(List.of(new Take(kit, null, 2))));
            assertThat(refused).hasSize(1);
            assertThat(stock(pads)).isEqualTo(5);
            assertThat(stock(fluid)).isEqualTo(1);

            List<Take> taken = tx.execute(_ -> sellable.take(List.of(new Take(kit, null, 1))));
            assertThat(taken).isEmpty();
            assertThat(stock(pads)).isEqualTo(3);
            assertThat(stock(fluid)).isZero();
            assertThat(sellable.find(List.of(new Item(kit, null)), "en")
                            .getFirst()
                            .stock())
                    .isZero();

            tx.executeWithoutResult(_ -> sellable.giveBack(List.of(new Take(kit, null, 1))));
            assertThat(stock(pads)).isEqualTo(5);
            assertThat(stock(fluid)).isEqualTo(1);
        }
    }

    @Nested
    class VariantImages {

        String upload(Business biz) throws Exception {
            return json(mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                                    .file(new MockMultipartFile(
                                            "file", "v.png", "image/png", png(1200, 1200, Color.WHITE)))
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated()))
                    .get("id")
                    .asString();
        }

        String withVariants(String sku, String imageIds) {
            return completeProduct("Blades", sku, 1900)
                    .replace(
                            "\"stock\":10,",
                            "\"stock\":10,\"variantTheme\":\"length\",\"variants\":[{\"value\":\"20 in\",\"sku\":\""
                                    + sku
                                    + "-20\",\"priceCents\":1700,\"stock\":2,\"imageIds\":" + imageIds
                                    + "},{\"value\":\"22 in\",\"sku\":\"" + sku
                                    + "-22\",\"priceCents\":1900,\"stock\":2}],");
        }

        @Test
        void aVariantKeepsItsOwnImages_andOnlyTheBusinessOwnUploads() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var other = seller(MerchantRole.OWNER);
            var mine = upload(biz);
            var sku = "VI-" + biz.merchantId().substring(20);
            var id = json(mvc.perform(postJson(PRODUCTS, withVariants(sku, "[\"" + mine + "\"]"), biz.merchantId())
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.variants[0].images[0].id").value(mine))
                            .andExpect(jsonPath("$.variants[0].images[0].url")
                                    .value("/api/v1/merchants/%s/media/%s".formatted(biz.merchantId(), mine)))
                            .andExpect(jsonPath("$.variants[1].images").isEmpty()))
                    .get("id")
                    .asString();
            mvc.perform(get(LISTING, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.variants[0].images[0].id").value(mine));

            var theirs = upload(other);
            mvc.perform(putJson(PRODUCTS + "/{id}", withVariants(sku, "[\"" + theirs + "\"]"), biz.merchantId(), id)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("variants[0].imageIds"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("That image is no longer available — upload it again."));
        }
    }

    @Nested
    class Documents {

        static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

        @Test
        void ownerUploadsListsDownloadsAndRemovesASpecSheet() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = product(biz, "DOC-" + biz.merchantId().substring(20), 5);
            var doc = json(mvc.perform(multipart(DOCUMENTS, biz.merchantId(), id)
                                    .file(new MockMultipartFile(
                                            "file", "C:\\specs\\wiper spec.pdf", "application/pdf", PDF))
                                    .param("purpose", "spec_sheet")
                                    .with(TestJwt.member(biz.userId())))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.purpose").value("spec_sheet"))
                            .andExpect(jsonPath("$.fileName").value("wiper spec.pdf"))
                            .andExpect(jsonPath("$.contentType").value("application/pdf")))
                    .get("id")
                    .asString();
            mvc.perform(get(DOCUMENTS, biz.merchantId(), id).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].id").value(doc));
            mvc.perform(get(DOCUMENTS + "/{d}", biz.merchantId(), id, doc).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                    .andExpect(content().bytes(PDF));
            mvc.perform(delete(DOCUMENTS + "/{d}", biz.merchantId(), id, doc).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            mvc.perform(get(DOCUMENTS + "/{d}", biz.merchantId(), id, doc).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void onlyPdfPngOrJpeg_andAPurpose() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = product(biz, "DT-" + biz.merchantId().substring(20), 5);
            mvc.perform(multipart(DOCUMENTS, biz.merchantId(), id)
                            .file(new MockMultipartFile(
                                    "file", "spec.pdf", "application/pdf", "<html>".getBytes(StandardCharsets.UTF_8)))
                            .param("purpose", "invoice")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Upload a PDF, PNG or JPEG under 10 MB."));
            mvc.perform(multipart(DOCUMENTS, biz.merchantId(), id)
                            .file(new MockMultipartFile("file", "spec.pdf", "application/pdf", PDF))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose spec sheet or invoice."));
        }

        @Test
        void membersOnly_editorsUpload_andNeverAnotherBusinessDocuments() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var id = product(biz, "DM-" + biz.merchantId().substring(20), 5);
            var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
            var upload = multipart(DOCUMENTS, biz.merchantId(), id)
                    .file(new MockMultipartFile("file", "i.pdf", "application/pdf", PDF))
                    .param("purpose", "invoice");
            mvc.perform(upload.with(TestJwt.member(bookkeeper))).andExpect(status().isForbidden());
            mvc.perform(get(DOCUMENTS, biz.merchantId(), id).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
            mvc.perform(get(DOCUMENTS, biz.merchantId(), id).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get(DOCUMENTS, biz.merchantId(), id).with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden());
            var other = seller(MerchantRole.OWNER);
            mvc.perform(get(DOCUMENTS, other.merchantId(), id).with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }
    }
}
